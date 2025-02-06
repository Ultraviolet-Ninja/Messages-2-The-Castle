package jasmine.jragon;

import jasmine.jragon.dropbox.cli.command.DropboxSession;
import jasmine.jragon.dropbox.cli.command.RemoveCommand;
import jasmine.jragon.dropbox.model.v2.DbxLongListFileInfo;
import jasmine.jragon.dropbox.model.v2.movement.FileMove;
import jasmine.jragon.dropbox.model.v2.movement.advanced.PageContentIndex;
import jasmine.jragon.mega.eliux.v2.Mega;
import jasmine.jragon.mega.eliux.v2.MegaSession;
import jasmine.jragon.mega.eliux.v2.auth.MegaAuthSessionID;
import jasmine.jragon.stream.support.IntermediateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static jasmine.jragon.FileTransferManager.conductFileTransfer;
import static jasmine.jragon.LocalFileManager.attemptFileDeletion;
import static jasmine.jragon.dropbox.DropboxFunctionManager.splitDropboxFoldersAndFiles;
import static jasmine.jragon.dropbox.DropboxFunctionManager.reduceRetrievalList;
import static jasmine.jragon.dropbox.DropboxFunctionManager.retrieveSimpleFileMovements;
import static jasmine.jragon.mega.MegaFunctionManager.crashCloudDirectory;
import static jasmine.jragon.mega.MegaFunctionManager.createAnnotationSubdirectories;
import static java.util.concurrent.CompletableFuture.runAsync;

public final class MegaUpload {
    private static final String LOGGING_BOUNDARY = '+' + "-".repeat(40) + '+';
    private static final String SESSION_ID = "";

    private static final String REVISION_FILE_NAME = "revision-list.txt";

    private static final String CRASH_CLOUD_DIR_ARG = "Crash-Cloud-Path",
            CLOUD_DIRECTORY_ARG = "Directory";

    private static final boolean DBX_FILES = true, DBX_FOLDERS = false;

    private static final Logger LOG = LoggerFactory.getLogger(MegaUpload.class);

    public static void main(String[] args) {
        LOG.info(LOGGING_BOUNDARY);
        var commandMap = parseCommandLineArgs(args);
        if (!commandMap.isEmpty()) {
            LOG.info("Command Map: {}", commandMap);
        }

        long start = System.nanoTime();

        try {
            executeFileTransfer(commandMap);
        } catch (InterruptedException e) {
            LOG.error("Interruption Exception at ls command: ", e);
        }

        logRuntime(System.nanoTime() - start);
    }

    private static void executeFileTransfer(Map<String, String> commandMap) throws InterruptedException {
        var dropboxSession = new DropboxSession(false);

        LOG.info("Logging into Mega Cloud");
        var megaCloudSession = Mega.login(new MegaAuthSessionID(SESSION_ID));
        LOG.info("Who Am I: {}", megaCloudSession.whoAmI());

        var downloadDestinationDirectory = setFileDownloadLocation(commandMap);

        if (commandMap.containsKey(CRASH_CLOUD_DIR_ARG) &&
                commandMap.get(CRASH_CLOUD_DIR_ARG).equalsIgnoreCase("True")) {
            crashCloudDirectory(megaCloudSession, doesRevisionFileExist(), REVISION_FILE_NAME);
        }

        var filesFoldersMapOptional = splitDropboxFoldersAndFiles(dropboxSession);

        if (filesFoldersMapOptional.isEmpty()) {
            return;
        }

        var filesAndFolders = filesFoldersMapOptional.get();
        var annotationsDirectoryFutureOpt = refreshAnnotationSubdirectories(megaCloudSession,
                filesAndFolders.get(DBX_FOLDERS));

        var dropboxFilePaths = filesAndFolders.get(DBX_FILES);
        dropboxFilePaths.sort(DbxLongListFileInfo::compareByFilename);

        conductFileMovements(dropboxFilePaths);

        var filePathsClone = new ArrayList<>(dropboxFilePaths);

        if (doesRevisionFileExist()) {
            reduceRetrievalList(dropboxFilePaths, REVISION_FILE_NAME);
        }

        if (dropboxFilePaths.isEmpty()) {
            LOG.info("No changes detected. Shutting down");
            return;
        }

        var overwriteRevisionFileFuture = runAsync(() -> overwriteRevisionFile(filePathsClone));

        var errorsContentIndexDuo = conductFileTransfer(dropboxFilePaths, dropboxSession,
                megaCloudSession, downloadDestinationDirectory);
        conductAdvancedFileMoves(errorsContentIndexDuo.second());

        try {
            overwriteRevisionFileFuture.get();
            cleanUpErrorFromRevisionFile(errorsContentIndexDuo.first());

            if (annotationsDirectoryFutureOpt.isPresent()) {
                annotationsDirectoryFutureOpt.get().get();
            }
        } catch (ExecutionException e) {
            LOG.error("Revision File Overwrite Error: ", e);
        }
    }

    private static String setFileDownloadLocation(Map<String, String> commandMap) {
        return commandMap.getOrDefault(CLOUD_DIRECTORY_ARG, "");
    }

    private static Optional<CompletableFuture<Void>> refreshAnnotationSubdirectories(MegaSession megaCloudSession, List<DbxLongListFileInfo> dropboxFolders) {
        var dayOfTheWeek = LocalDate.now().getDayOfWeek();
        return dayOfTheWeek == DayOfWeek.SATURDAY ?
                Optional.of(runAsync(() -> createAnnotationSubdirectories(
                        megaCloudSession, dropboxFolders))) :
                Optional.empty();
    }

    private static boolean doesRevisionFileExist() {
        var f = new File(REVISION_FILE_NAME);
        boolean isFound = f.exists() && !f.isDirectory();
        LOG.debug("Revision File Exists: {}", isFound);
        return isFound;
    }

    private static void conductFileMovements(List<DbxLongListFileInfo> dropboxFilePaths) {
        var fileMovements = retrieveSimpleFileMovements(dropboxFilePaths);
        if (fileMovements.isEmpty()) {
            return;
        }

        var dropboxSession = new DropboxSession(false);
        Consumer<RemoveCommand> removeOldFile = removeCommand -> {
            try {
                var output = removeCommand.execute();
                if (output.isError()) {
                    LOG.warn(output.errorMessage());
                }
            } catch (InterruptedException e) {
                LOG.error("Timeout: ", e);
            } catch (IllegalStateException e) {
                LOG.error("Operation took too long: ", e);
            }
        };

        fileMovements.stream()
                .peek(fileMove -> LOG.info(String.valueOf(fileMove)))
                .map(FileMove::getOlderFile)
                .flatMap(IntermediateUtils::eliminateOptional)
                .peek(dropboxFilePaths::remove)
                .map(DbxLongListFileInfo::toString)
                .map(dropboxSession::remove)
                .forEach(removeOldFile);
    }

    private static void conductAdvancedFileMoves(PageContentIndex contentIndex) {
        contentIndex.traceDuplicateIndexes();
    }

    private static void overwriteRevisionFile(List<DbxLongListFileInfo> fileInfoList) {
        var sb = new StringBuilder();
        fileInfoList.forEach(file -> sb.append(file.getCurrentFileHash())
                .append(',')
                .append(file)
                .append('\n'));

        try (var writer = new BufferedWriter(new FileWriter(REVISION_FILE_NAME))) {
            writer.write(sb.toString());
            LOG.info("Revision file overwritten");
        } catch (IOException e) {
            LOG.warn("Overwrite File Error", e);
        }
    }

    private static void cleanUpErrorFromRevisionFile(List<String> erroneousFiles) {
        if (!erroneousFiles.isEmpty()) {
            LOG.warn("Error Files: {}", erroneousFiles);
            clearErrorsFromRevisionFile(erroneousFiles);
        }
    }

    private static void clearErrorsFromRevisionFile(List<String> erroneousFiles) {
        String filteredFileContent;
        var erroneousFileSet = new HashSet<>(erroneousFiles);
        try (var reader = new BufferedReader(new FileReader(REVISION_FILE_NAME))) {
            filteredFileContent = reader.lines()
                    .map(line -> line.split(","))
                    .filter(array -> !erroneousFileSet.contains(array[1]))
                    .map(array -> String.format("%s,%s", array[0], array[1]))
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            LOG.error("File Read error", e);
            return;
        }

        if (!attemptFileDeletion(REVISION_FILE_NAME)) {
            LOG.warn("Cannot rewrite Revision file");
            return;
        }

        try (var writer = new BufferedWriter(new FileWriter(REVISION_FILE_NAME))) {
            writer.write(filteredFileContent);
        } catch (IOException e) {
            LOG.error("File Write error", e);
        }
    }

    private static Map<String, String> parseCommandLineArgs(String[] args) {
        Predicate<String> logBadArguments = arg -> {
            if (arg.matches("[\\w\\-]+:[\\w\\-]+")) {
                return true;
            }
            LOG.warn("'{}' argument ignored", arg);
            return false;
        };

        return Arrays.stream(args)
                .filter(logBadArguments)
                .map(arg -> arg.split(":"))
                .collect(Collectors.toMap(
                        array -> array[0],
                        array -> array[1]
                ));
    }

    private static void logRuntime(long timeToRun) {
        Duration elapsedTime = Duration.ofNanos(timeToRun);
        long elapsedTimeInMinutes = elapsedTime.toMinutesPart();
        long elapsedTimeInSeconds = elapsedTime.toSecondsPart();

        String minuteFormat = "minute" + (elapsedTimeInMinutes == 1 ? "" : "s");
        String secondFormat = "second" + (elapsedTimeInSeconds == 1 ? "" : "s");

        LOG.info("Duration: {} {} - {} {}", elapsedTimeInMinutes, minuteFormat, elapsedTimeInSeconds, secondFormat);
    }
}
