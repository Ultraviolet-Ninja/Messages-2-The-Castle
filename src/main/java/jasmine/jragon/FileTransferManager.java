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
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarStyle;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static jasmine.jragon.LocalResourceManager.attemptFileDeletion;
import static jasmine.jragon.dropbox.DropboxFunctionManager.downloadFile;
import static jasmine.jragon.mega.MegaFunctionManager.sendFileToMega;
import static jasmine.jragon.progress.bar.ProgressBarGenerator.generateProgressBar;
import static jasmine.jragon.progress.bar.ProgressBarGenerator.generateProgressBarStyle;
import static jasmine.jragon.progress.bar.ProgressBarGenerator.generateSubProgressBar;

public final class FileTransferManager {
    private static final Logger LOG = LoggerFactory.getLogger(FileTransferManager.class);

    private static final int CONCURRENCY_THRESHOLD = 20;
    private static final int CONCURRENCY_COUNT = 4;
    private static final int DOWNSIZED_CAPACITY = 50;

    private static final String PROGRESS_BAR_TITLE = "Transporting Documents";
    private static final String UNIT_NAME = " PDFs";
    private static final int UNIT_COUNT = 1;

    private static final ProgressBarStyle RUNTIME_SUB_BAR_STYLE = generateProgressBarStyle();

    static @NotNull Duo<List<String>, PageContentIndex> conductFileTransfer(
            @NonNull List<DbxLongListFileInfo> dropboxFiles, @NonNull DropboxSession dropboxSession,
            @NonNull MegaSession megaCloudSession, @NonNull String downloadDestinationDirectory,
            boolean crashDirectory) {
        int transferSize = dropboxFiles.size();
        boolean remainSequential = transferSize < CONCURRENCY_THRESHOLD;

        var contentIndex = crashDirectory ?
                new PageContentIndex(!remainSequential) :
                new PageContentIndex(!remainSequential, DOWNSIZED_CAPACITY);

        List<String> erroneousFiles = remainSequential ?
                new ArrayList<>() :
                new CopyOnWriteArrayList<>();

        try (var topLevelProgressBar = generateProgressBar(transferSize,
                PROGRESS_BAR_TITLE, UNIT_NAME, UNIT_COUNT, generateProgressBarStyle())) {
            var fileTransferAction = curryTransferFunction(
                    dropboxSession, megaCloudSession, downloadDestinationDirectory,
                    contentIndex, erroneousFiles, topLevelProgressBar
            );

            if (remainSequential) {
                try (var fileLevelProgressBar = generateSubProgressBar("Main Thread", RUNTIME_SUB_BAR_STYLE)) {
                    fileTransferAction.accept(dropboxFiles, fileLevelProgressBar);
                }
            } else {
                LOG.debug("Concurrency Enabled");
                conductConcurrentFileTransfer(dropboxFiles, fileTransferAction);
            }
        }

        return Duo.of(erroneousFiles, contentIndex);
    }

    private static BiConsumer<List<DbxLongListFileInfo>, ProgressBar> curryTransferFunction(
            DropboxSession dropboxSession, MegaSession megaCloudSession,
            String downloadDestinationDirectory, PageContentIndex contentIndex,
            List<String> erroneousFiles, ProgressBar topLevelProgressBar) {
        Function<DbxLongListFileInfo, GetCommand> createDropboxGetCommand = downloadDestinationDirectory.isBlank() ?
                file -> dropboxSession.getFile(file.toString()) :
                file -> dropboxSession.getFile(file.toString(), downloadDestinationDirectory);

        Function<String, IntermediateFile> createIntermediateFile = downloadDestinationDirectory.isBlank() ?
                IntermediateFile::new :
                dropboxFile -> new IntermediateFile(dropboxFile, downloadDestinationDirectory);

        BiConsumer<PDDocument, String> populateIndex = (document, dropboxFile) -> {
            try {
                contentIndex.addDocument(dropboxFile, document.getPages());
            } catch (IllegalArgumentException e) {
                LOG.warn("Fucking Duh: {}", e.getMessage());
            }
        };

        return (cloudPath, subProgressBar) -> cloudPath.stream()
                .peek(dbxFile -> {
                    subProgressBar.reset();
                    subProgressBar.setExtraMessage(dbxFile.getFileName());
                })
                .map(createDropboxGetCommand)
                .map(getCommand -> {
                    var downloadOpt = downloadFile(getCommand, erroneousFiles);

                    if (downloadOpt.isPresent()) {
                        subProgressBar.step();
                    } else {
                        subProgressBar.reset();
                    }
                    return downloadOpt;
                })
                .flatMap(IntermediateUtils::eliminateOptional)
                //Create the intermediate file
                .map(createIntermediateFile)
                .forEach(file -> {
                    /*
                     * Populating a content index to look for more complicated file moves
                     * that include a change in file names or
                     * movements with longer distances than what the simple moves can detect
                     */
                    file.operateOnDropboxPDF(populateIndex);

                    //Insert the text boxes before uploading to MEGA
                    PDFEditor.customizeDocFile(file);
                    subProgressBar.step();

                    sendFileToMega(file, megaCloudSession, erroneousFiles);
                    subProgressBar.step();
                    attemptFileDeletion(file.createLocalFileObject());
                    subProgressBar.setExtraMessage("");

                    topLevelProgressBar.step();
                });
    }

    private static void conductConcurrentFileTransfer(List<DbxLongListFileInfo> dropboxFiles,
                                                      BiConsumer<List<DbxLongListFileInfo>, ProgressBar> transferConsumer) {
        /*
         * Some files will have the same filename, which will cause problems
         * if we download X.pdf from one directory, then it gets overwritten
         * by X.pdf from another directory
         *
         * So, we sort them by specifically the filename (excluding the path)
         * to mitigate the amount of overwriting, presumably to 0,
         * unless the amount of files with the same name exceeds 2 FJ partition sizes
         */
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

//        Map<String, ProgressBar> subProgressBars = new ConcurrentHashMap<>(CONCURRENCY_COUNT + 1, 1.0f);
        Map<String, ProgressBar> subProgressBars = Collections.synchronizedMap(
                new HashMap<>(CONCURRENCY_COUNT + 1, 1.0f)
        );
        var forkJoinPool = new ForkJoinPool(CONCURRENCY_COUNT);

        forkJoinPool.invoke(new FileTransferTask(dropboxFiles, transferConsumer, subProgressBars));

        subProgressBars.values()
                .forEach(ProgressBar::close);
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
        private static final int LIST_PARTITION_SIZE = 10;

        @NonNull
        private final List<DbxLongListFileInfo> filePaths;
        @NonNull
        private final BiConsumer<List<DbxLongListFileInfo>, ProgressBar> transferConsumer;
        @NonNull
        private final Map<String, ProgressBar> subProgressBars;

        @Override
        protected void compute() {
            if (filePaths.size() > LIST_PARTITION_SIZE) {
                ForkJoinTask.invokeAll(divideTask());
            } else {
                var threadName = Thread.currentThread().getName();
                var currentSubProgressBar = subProgressBars.computeIfAbsent(
                        threadName,
                        thread -> generateSubProgressBar(thread, RUNTIME_SUB_BAR_STYLE)
                );
                transferConsumer.accept(filePaths, currentSubProgressBar);
            }
        }

        private List<FileTransferTask> divideTask() {
            int size = filePaths.size();
            int halfSize = size >> 1;
            var taskOne = new FileTransferTask(filePaths.subList(0, halfSize), transferConsumer, subProgressBars);
            var taskTwo = new FileTransferTask(filePaths.subList(halfSize, size), transferConsumer, subProgressBars);
            return new ArrayList<>(List.of(taskOne, taskTwo));
        }
    }
}
