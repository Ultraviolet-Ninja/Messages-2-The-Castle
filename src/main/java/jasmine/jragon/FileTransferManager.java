package jasmine.jragon;

import jasmine.jragon.dropbox.cli.command.DropboxSession;
import jasmine.jragon.dropbox.cli.command.GetCommand;
import jasmine.jragon.dropbox.model.v2.DbxLongListFileInfo;
import jasmine.jragon.dropbox.model.v2.IntermediateFile;
import jasmine.jragon.dropbox.model.v2.movement.advanced.PageContentIndex;
import jasmine.jragon.mega.eliux.v2.MegaSession;
import jasmine.jragon.pdf.PDFEditor;
import jasmine.jragon.stream.support.IntermediateUtils;
import jasmine.jragon.tuple.type.Duo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.function.Consumer;
import java.util.function.Function;

import static jasmine.jragon.LocalResourceManager.attemptFileDeletion;
import static jasmine.jragon.dropbox.DropboxFunctionManager.downloadFile;
import static jasmine.jragon.mega.MegaFunctionManager.sendFileToMega;

public final class FileTransferManager {
    private static final Logger LOG = LoggerFactory.getLogger(FileTransferManager.class);

    private static final int CONCURRENCY_THRESHOLD = 20;
    private static final int CONCURRENCY_COUNT = 4;
//    private static final int NEW_CONCURRENCY_COUNT = 3;

    static @NotNull Duo<List<String>, PageContentIndex> conductFileTransfer(
            @NonNull List<DbxLongListFileInfo> dropboxFiles, @NonNull DropboxSession dropboxSession,
            @NonNull MegaSession megaCloudSession, @NonNull String downloadDestinationDirectory) {

        boolean remainSequential = dropboxFiles.size() < CONCURRENCY_THRESHOLD;

        var contentIndex = new PageContentIndex(!remainSequential);
        List<String> erroneousFiles = remainSequential ?
                new ArrayList<>() :
                Collections.synchronizedList(new ArrayList<>());

        Function<DbxLongListFileInfo, GetCommand> createDropboxGetCommand = downloadDestinationDirectory.isEmpty() ?
                file -> dropboxSession.getFile(file.toString()) :
                file -> dropboxSession.getFile(file.toString(), downloadDestinationDirectory);

        Function<String, IntermediateFile> createIntermediateFile = downloadDestinationDirectory.isEmpty() ?
                IntermediateFile::new :
                dropboxFile -> new IntermediateFile(dropboxFile, downloadDestinationDirectory);

        Consumer<IntermediateFile> populateIndex = intermediateFile -> {
            try (var document = intermediateFile.createPDF()) {
                contentIndex.addDocument(intermediateFile.getDropboxFilePath(), document.getPages());
            } catch (IOException e) {
                LOG.warn("{} triggered an issue during indexing: {}", intermediateFile, e.getMessage());
            } catch (IllegalArgumentException e) {
                LOG.warn("Fucking Duh: {}", e.getMessage());
            }
        };

        Consumer<List<DbxLongListFileInfo>> fileTransferAction = cloudPath -> cloudPath.stream()
                .map(createDropboxGetCommand)
                .map(getCommand -> downloadFile(getCommand, erroneousFiles))
                .flatMap(IntermediateUtils::eliminateOptional)
                //Create the intermediate file
                .map(createIntermediateFile)
                //Populating a content index to look for more complicated file moves
                //that include a change in file names
                .peek(populateIndex)
                //Insert the text boxes before uploading to MEGA
                .peek(PDFEditor::customizeDocFile)
                .peek(file -> sendFileToMega(file, megaCloudSession, erroneousFiles))
                .forEach(file -> attemptFileDeletion(file.createLocalFileObject()));

        if (remainSequential) {
            fileTransferAction.accept(dropboxFiles);
        } else {
            LOG.debug("Concurrency Enabled");
            conductConcurrentFileTransfer(dropboxFiles, fileTransferAction);
        }

        return Duo.of(erroneousFiles, contentIndex);
    }

    private static void conductConcurrentFileTransfer(List<DbxLongListFileInfo> dropboxFiles,
                                                      Consumer<List<DbxLongListFileInfo>> transferConsumer) {
        dropboxFiles.sort(DbxLongListFileInfo::compareByFilename);

//        var futureList = partitionList(dropboxFiles)
//                .stream()
//                .map(subList -> CompletableFuture.runAsync(() -> transferConsumer.accept(subList)))
//                .collect(Collectors.toList());
//
//        for (var future : futureList) {
//            try {
//                future.get();
//            } catch (ExecutionException | InterruptedException e) {
//                LOG.error(e.getMessage(), e);
//            }
//        }

        var forkJoinPool = new ForkJoinPool(CONCURRENCY_COUNT);

        forkJoinPool.invoke(new FileTransferTask(dropboxFiles, transferConsumer));
    }

//    private static <T> List<List<T>> partitionList(List<T> list) {
//        List<List<T>> output = new ArrayList<>();
//        int listSize = list.size();
//        int elementsPerPartition = (int) Math.ceil((double) listSize / NEW_CONCURRENCY_COUNT);
//
//        for (int i = 0; i < listSize; i += elementsPerPartition) {
//            int endIndex = Math.min(i + elementsPerPartition, listSize);
//            output.add(list.subList(i, endIndex));
//        }
//
//        return output;
//    }

    @RequiredArgsConstructor
    private static final class FileTransferTask extends RecursiveAction {
        private static final int LIST_PARTITION_SIZE = 5;

        @NonNull
        private final List<DbxLongListFileInfo> filePaths;
        @NonNull
        private final Consumer<List<DbxLongListFileInfo>> transferConsumer;

        @Override
        protected void compute() {
            if (filePaths.size() > LIST_PARTITION_SIZE) {
                ForkJoinTask.invokeAll(divideTask());
            } else {
                transferConsumer.accept(filePaths);
            }
        }

        private List<FileTransferTask> divideTask() {
            int size = filePaths.size();
            int halfSize = size >> 1;
            var taskOne = new FileTransferTask(filePaths.subList(0, halfSize), transferConsumer);
            var taskTwo = new FileTransferTask(filePaths.subList(halfSize, size), transferConsumer);
            return new ArrayList<>(List.of(taskOne, taskTwo));
        }
    }
}
