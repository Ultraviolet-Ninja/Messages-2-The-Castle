package jasmine.jragon.dropbox.model.v2;

import lombok.Getter;
import lombok.NonNull;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

@Getter
public final class IntermediateFile {
    public static final String MEGA_CLOUD_NOTE_BASE_PATH = "Castle in the Sky/Boox-Notes";

    //If a new directory were to appear, then we can add it as the next class constant at package access level
    static final String OLD_DROPBOX_NOTE_BASE_PATH = "/Apps/onyx-knote/NoteAir2-Notepads";
    static final String SECOND_DROPBOX_NOTE_BASE_PATH = "/Apps/onyx-knote/onyx/NoteAir2/Notepads";

    private final String dropboxFilePath, megaCloudPath, localFile;

    public IntermediateFile(@NonNull String dropboxFilePath) {
        this.dropboxFilePath = dropboxFilePath;
        localFile = dropboxFilePath.substring(dropboxFilePath.lastIndexOf('/') + 1);
        megaCloudPath = createMegaCloudPath(dropboxFilePath);
    }

    public IntermediateFile(@NonNull String dropboxFilePath, @NonNull String downloadDestination) {
        this.dropboxFilePath = dropboxFilePath;
        localFile = downloadDestination + dropboxFilePath.substring(dropboxFilePath.lastIndexOf('/') + 1);
        megaCloudPath = createMegaCloudPath(dropboxFilePath);
    }

    private static String createMegaCloudPath(String dropboxFilePath) {
        var path = dropboxFilePath.replace(OLD_DROPBOX_NOTE_BASE_PATH, "")
                .replace(SECOND_DROPBOX_NOTE_BASE_PATH, "")
                //Going from Boox to Dropbox, it presumably eliminates special characters
                //and the & is the one that I use in file names
                .replace("  ", " & ");

        return new StringBuilder(path)
                .insert(0, MEGA_CLOUD_NOTE_BASE_PATH)
                .toString();
    }

    @Contract(" -> new")
    public @NotNull File createLocalFileObject() {
        return new File(localFile);
    }

    public PDDocument createPDF() throws IOException {
        return Loader.loadPDF(createLocalFileObject());
    }

    @Contract(pure = true)
    @Override
    public @NotNull String toString() {
        return String.format("Local - %s%nDropbox - %s%nMega Cloud - %s%n",
                localFile, dropboxFilePath, megaCloudPath);
    }
}
