package jasmine.jragon;

import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public final class LocalFileManager {
    private static final Logger LOG = LoggerFactory.getLogger(LocalFileManager.class);

    public static boolean attemptFileDeletion(@NonNull File file) {
        String name = file.getName();
        if (file.delete()) {
            LOG.debug("Deleted file {}", name);
            return true;
        } else {
            LOG.warn("Deleted attempted on file {}, but failed", name);
            return false;
        }
    }

    public static boolean attemptFileDeletion(String filename) {
        return attemptFileDeletion(new File(filename));
    }

    public static boolean attemptFileRename(File current, File desired) {
        if (current.renameTo(desired)) {
            LOG.info("Rename Success: {} -> {}", current, desired);
            return true;
        } else {
            LOG.warn("Rename Failed: {} X {}", current, desired);
            return false;
        }
    }
}
