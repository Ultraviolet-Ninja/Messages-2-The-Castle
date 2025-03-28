package jasmine.jragon;

import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public final class LocalResourceManager {
    private static final Logger LOG = LoggerFactory.getLogger(LocalResourceManager.class);

    public static boolean attemptFileDeletion(@NonNull File resource) {
        var name = resource.getName();
        if (resource.delete()) {
            LOG.debug("Deleted {}", name);
            return true;
        } else {
            LOG.warn("Deleted attempted on {}, but failed", name);
            return false;
        }
    }

    public static boolean attemptFileDeletion(String filename) {
        return attemptFileDeletion(new File(filename));
    }

    public static boolean attemptFileRename(File current, File desired) {
        if (current.renameTo(desired)) {
            LOG.debug("Rename Success: {} -> {}", current, desired);
            return true;
        } else {
            LOG.warn("Rename Failed: {} X {}", current, desired);
            return false;
        }
    }

    public static String getThreadName() {
        return Thread.currentThread().getName();
    }
}
