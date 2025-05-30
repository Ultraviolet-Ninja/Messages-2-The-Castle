package jasmine.jragon.dropbox;

import jasmine.jragon.dropbox.cli.command.DropboxSession;
import jasmine.jragon.dropbox.cli.command.GetCommand;
import jasmine.jragon.dropbox.cli.command.RemoveCommand;
import jasmine.jragon.dropbox.cli.model.DropboxProcessResponse;
import jasmine.jragon.dropbox.model.v2.DbxLongListFileInfo;
import jasmine.jragon.dropbox.model.v2.movement.simple.FileMove;
import jasmine.jragon.stream.collector.restream.grouping.KeylessGroup;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static jasmine.jragon.LocalResourceManager.isRunningOnMac;

public final class DropboxFunctionManager {
    private static final double DROP_OFF_RATE = 0.5;
    private static final Logger LOG = LoggerFactory.getLogger(DropboxFunctionManager.class);

    private static final String ROOT_DIR = "/Apps";

    public static Optional<String> downloadFile(@NotNull GetCommand command, List<String> errorFileList) {
        try {
            var response = command.execute();

            if (response.isSuccessful()) {
                logSuccessfulResponse(response.toString());
                return Optional.of(command.getFile());
            }

            LOG.warn("{}", response);
        } catch (InterruptedException e) {
            LOG.error("Timeout Exception: ", e);
        }

        errorFileList.add(command.getFile());
        return Optional.empty();
    }

    private static void logSuccessfulResponse(String rawResults) {
        var downloadSnippets = Arrays.stream(rawResults.split("\n"))
                .map(s -> s.replace("Downloading ", ""))
                .map(s -> s.substring(0, s.indexOf('/')))
                .distinct()
                .toList();

        LOG.trace("Downloading: {}/{}",
                String.join(".... ", downloadSnippets),
                downloadSnippets.get(downloadSnippets.size() - 1)
        );
    }

    public static Optional<Map<Boolean, List<DbxLongListFileInfo>>> splitDropboxFoldersAndFiles(@NotNull DropboxSession session)
            throws InterruptedException {
        DropboxProcessResponse<List<String>> listDirectoryResponse = null;
        boolean retryCommand;
        double dropOffMultiplier = 1.5;

        do {
            var listDirectoryCommand = isRunningOnMac() ?
                    session.list(true, true, ROOT_DIR) :
                    session.list(true, true);

            try {
                listDirectoryResponse = listDirectoryCommand.execute(dropOffMultiplier);
                retryCommand = false;
            } catch (IllegalArgumentException e) {
                LOG.warn("Probably premature return. Retrying command execution");
                retryCommand = true;
                dropOffMultiplier += DROP_OFF_RATE;
            }
        } while (retryCommand);

        if (listDirectoryResponse.isError()) {
            LOG.error("Error Code: {}\nErrorMessage: {}\nAborting Operation",
                    listDirectoryResponse.statusCode(),
                    listDirectoryResponse.errorMessage());
            return Optional.empty();
        }

        var fileFolderSplit = listDirectoryResponse.successObject()
                .stream()
		        .skip(1)
                .map(String::trim)
                .map(DbxLongListFileInfo::new)
                .collect(Collectors.partitioningBy(DbxLongListFileInfo::isFile));
        return Optional.of(fileFolderSplit);
    }

    public static void reduceRetrievalList(@NotNull List<DbxLongListFileInfo> dropboxFilePaths,
                                           String revisionFile) {
        try (var reader = new BufferedReader(new FileReader(revisionFile))) {
            final var mostRecentFileRevisions = reader.lines()
                    .filter(line -> !line.isEmpty())
                    .map(line -> line.split(",")[0])
                    .collect(Collectors.toUnmodifiableSet());

//            var fileIterator = dropboxFilePaths.iterator();
//
//            while (fileIterator.hasNext()) {
//                String file = fileIterator.next();
//                var revisionCommand = session.listRevisions(file);
//                try {
//                    var response = revisionCommand.execute();
//
//                    response.ifSuccessful(fileRevisionHistory -> {
//                        if (isFileUpToDate(fileRevisionHistory, mostRecentFileRevisions)) {
//                            fileIterator.remove();
//                        }
//                    });
//                } catch (InterruptedException e) {
//                    LOG.warn("Interruption Error at {}", file);
//                }
//            }

            var permittedPaths = dropboxFilePaths.parallelStream()
                    .filter(file -> !mostRecentFileRevisions.contains(file.getCurrentFileHash()))
                    .toList();

            dropboxFilePaths.clear();
            dropboxFilePaths.addAll(permittedPaths);
        } catch (IOException e) {
            LOG.warn("IO Exception: ", e);
        }
    }

    public static List<FileMove> retrieveSimpleFileMovements(List<DbxLongListFileInfo> files) {
        return files.stream()
                //Extract the filename and use it as the group name
                .collect(KeylessGroup.provide(DbxLongListFileInfo::getFileName))
                //Any group that is only one file means it's got a unique name and has not been moved
                .filter(fileInfoList -> fileInfoList.size() > 1)
                .flatMap(DropboxFunctionManager::permuteListToFileMoves)
                .toList();
    }

    private static Stream<FileMove> permuteListToFileMoves(List<DbxLongListFileInfo> files) {
	    Stream.Builder<FileMove> output = Stream.builder();
        for (var left : files) {
            for (var right : files) {
                if (left != right) {
                    output.accept(new FileMove(left, right));
                }
            }
        }
        return output.build()
                .filter(FileMove::isValidMove)
                .distinct();
    }

    public static void removeDropboxResource(@NotNull RemoveCommand removeCommand) {
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
    }

    public static void wipeEmptyDirectories(@NonNull @Unmodifiable List<String> files,
                                            @NonNull @Unmodifiable List<String> folders,
                                            @NonNull DropboxSession session) {
        var fileGroups = files.stream()
                .map(DropboxFunctionManager::getPathToResource)
                .collect(Collectors.toUnmodifiableSet());

        var folderGroups = folders.stream()
                .map(DropboxFunctionManager::getPathToResource)
                .collect(Collectors.toUnmodifiableSet());

        var reportedEmptyFolders = folders.stream()
                .filter(f -> !fileGroups.contains(f) && !folderGroups.contains(f))
                .toList();

        if (!reportedEmptyFolders.isEmpty()) {
            LOG.trace("Reported empty folders: {}", reportedEmptyFolders);

            reportedEmptyFolders.stream()
                    .map(session::remove)
                    .forEach(DropboxFunctionManager::removeDropboxResource);
        }
    }

    private static String getPathToResource(String resource) {
        return resource.substring(0, Math.max(resource.lastIndexOf('/'), 0));
    }
}
