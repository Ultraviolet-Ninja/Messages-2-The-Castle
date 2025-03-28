package jasmine.jragon.mega;

import jasmine.jragon.dropbox.model.v2.DbxLongListFileInfo;
import jasmine.jragon.dropbox.model.v2.movement.simple.FileMove;
import jasmine.jragon.dropbox.model.v2.IntermediateFile;
import jasmine.jragon.mega.eliux.v2.MegaSession;
import jasmine.jragon.mega.eliux.v2.cmd.MegaCmdPutSingle;
import jasmine.jragon.mega.eliux.v2.error.MegaException;
import jasmine.jragon.mega.eliux.v2.error.MegaResourceNotFoundException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static jasmine.jragon.LocalResourceManager.attemptFileDeletion;
import static jasmine.jragon.dropbox.model.v2.IntermediateFile.MEGA_CLOUD_NOTE_BASE_PATH;

public final class MegaFunctionManager {
    private static final Logger LOG = LoggerFactory.getLogger(MegaFunctionManager.class);
    private static final String ANNOTATION_DIRECTORY = "Castle in the Sky/Boox-Annotations";

    private static final List<String> DROPBOX_PATH_PREFIX_LIST;

    public static void crashCloudDirectory(MegaSession session, boolean doesRevisionFileExist,
                                           String filename) {
        if (doesRevisionFileExist) {
            attemptFileDeletion(filename);
        }
        LOG.info("Deleting current note directory in Mega");
        var removeCommand = session.removeDirectory(MEGA_CLOUD_NOTE_BASE_PATH);

        try {
            removeCommand.run();
        } catch (MegaResourceNotFoundException resourceException) {
            LOG.debug("Directory is already removed. Proceeding...");
        }
    }

    public static void sendFileToMega(@NotNull IntermediateFile intermediateFile, @NotNull MegaSession session,
                                      List<String> erroneousFiles) {
        var putCommand = session.uploadFile(true,
                intermediateFile.getLocalFile(), intermediateFile.getMegaCloudPath());
        executeUploadCommand(putCommand, erroneousFiles, intermediateFile.getDropboxFilePath());
    }

    private static void executeUploadCommand(MegaCmdPutSingle command, List<String> erroneousFileList,
                                             String dropboxFile) {
        try {
            command.run();
        } catch (MegaException e) {
            LOG.error(e.getMessage(), e);
            attemptFileDeletion(command.getLocalFile());
            //Stores the dropbox file for deletion later from the revision-list.txt file
            erroneousFileList.add(dropboxFile);
        }
    }

    public static void createAnnotationSubdirectories(@NotNull MegaSession megaSession,
                                                      @NotNull List<DbxLongListFileInfo> folders) {
        var failedPaths = folders.stream()
                .map(MegaFunctionManager::convertDropboxToMegaAnnotationPath)
                .filter(megaPath -> makeMegaDirectory(megaPath, megaSession))
                .toList();

        if (!failedPaths.isEmpty()) {
            LOG.warn("Failed Paths: {}", failedPaths);
        }
    }

    private static String convertDropboxToMegaAnnotationPath(DbxLongListFileInfo dropboxFolder) {
        String outputPath = dropboxFolder.toString();

        for (var prefixPath : DROPBOX_PATH_PREFIX_LIST) {
            outputPath = outputPath.replace(prefixPath, "");
        }

        return ANNOTATION_DIRECTORY + outputPath;
    }

    private static boolean makeMegaDirectory(String megaPath, MegaSession megaSession) {
        var command = megaSession.makeDirectory(megaPath).recursively();

        try {
            command.run();
            return false;
        } catch (MegaException e) {
            return true;
        }
    }

    static {
        DROPBOX_PATH_PREFIX_LIST = Stream.of(IntermediateFile.class)
                .map(Class::getDeclaredFields)
                .flatMap(Arrays::stream)
                .filter(FileMove::isPackageAccessStringConstant)
                .peek(field -> field.setAccessible(true))
                .map(FileMove::fetchValue)
                .filter(Objects::nonNull)
                .map(o -> (String)o)
                .toList();
    }
}
