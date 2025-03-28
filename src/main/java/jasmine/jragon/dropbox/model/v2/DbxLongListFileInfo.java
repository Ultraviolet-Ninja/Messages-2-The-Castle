package jasmine.jragon.dropbox.model.v2;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

@EqualsAndHashCode(doNotUseGetters = true, cacheStrategy = EqualsAndHashCode.CacheStrategy.LAZY)
public final class DbxLongListFileInfo {
    @Language("Regexp")
    private static final String FOLDER_REGEX = "-\\s+-\\s+-\\s+[a-zA-Z\\d+ /-]+";
    @Language("Regexp")
    private static final String BASE_DIRECTORY = "[a-f0-9]{21} +\\d+(?:\\.\\d)? [KM]iB\\s+\\d{1,2} (month|day|second|hour|minute|year|week)s? ago\\s+%s/[a-zA-Z\\d+ /\\-äöüÄÖÜ.()]+\\.pdf";
    private static final UnaryOperator<String> SUPPLY_BASE_DIRECTORY = directory -> String.format(BASE_DIRECTORY, directory);
    private static final String OLD_FILE_REGEX, SECOND_FILE_REGEX;

    static {
        OLD_FILE_REGEX = SUPPLY_BASE_DIRECTORY.apply(IntermediateFile.OLD_DROPBOX_NOTE_BASE_PATH);
        SECOND_FILE_REGEX = SUPPLY_BASE_DIRECTORY.apply(IntermediateFile.SECOND_DROPBOX_NOTE_BASE_PATH);
    }

    @Getter
    @NonNull
    private final String currentFileHash;
    @Getter
    private final double fileSize;
    @Getter
    @Nullable
    private final FileSizeType fileSizePrefix;
    @Getter
    private final int modificationAge;
    @Getter
    @Nullable
    private final ChronoUnit modificationAgeUnit;
    @NonNull
    private final String dropboxDirectory;
    @Getter
    @NonNull
    private final String noteFileSubdirectory;

    public DbxLongListFileInfo(@NonNull String line) throws IllegalArgumentException {
        if (line.isBlank()) {
            throw new IllegalArgumentException("Argument should not be blank");
        }

        boolean noExpressionMatched = Stream.of(FOLDER_REGEX, OLD_FILE_REGEX, SECOND_FILE_REGEX)
                .noneMatch(line::matches);

        if (noExpressionMatched) {
            throw new IllegalArgumentException("Argument doesn't match dropbox folder or file expression: " + line);
        }

        boolean isFolder = line.charAt(0) == '-';

        if (isFolder) {
            currentFileHash = "-";
            fileSize = -1;
            fileSizePrefix = null;
            modificationAge = -1;
            modificationAgeUnit = null;
        } else {
            var splitArray = line.split("\\s+", 7);
            currentFileHash = splitArray[0];
            fileSize = Double.parseDouble(splitArray[1]);

            fileSizePrefix = splitArray[2].contains(FileSizeType.KIBIBYTES.getAbbreviation()) ?
                    FileSizeType.KIBIBYTES :
                    FileSizeType.MEBIBYTES;

            modificationAge = Integer.parseInt(splitArray[3]);
            modificationAgeUnit = extractTimeUnit(splitArray[4]);
        }

        dropboxDirectory = line.substring(line.indexOf('/'));
        noteFileSubdirectory = isFolder ?
                "" :
                dropboxDirectory.replace(IntermediateFile.OLD_DROPBOX_NOTE_BASE_PATH, "")
                        .replace(IntermediateFile.SECOND_DROPBOX_NOTE_BASE_PATH, "");
    }

    private static ChronoUnit extractTimeUnit(String line) {
        return Stream.of(ChronoUnit.SECONDS, ChronoUnit.MINUTES, ChronoUnit.HOURS, ChronoUnit.DAYS,
                        ChronoUnit.WEEKS, ChronoUnit.MONTHS, ChronoUnit.YEARS)
                .filter(unit -> doesLineContainChronoUnit(unit, line))
                .findFirst()
                .orElse(null);
    }

    private static boolean doesLineContainChronoUnit(ChronoUnit unit, String line) {
        var timeUnitName = unit.toString().toLowerCase();
        timeUnitName = timeUnitName.substring(0, timeUnitName.length() - 1);
        return line.contains(timeUnitName);
    }

    public boolean isFolder() {
        return Objects.equals(currentFileHash, "-");
    }

    public boolean isFile() {
        return !"-".equals(currentFileHash);
    }

    public @NotNull String getFileName() {
        return isFile() ?
                dropboxDirectory.substring(dropboxDirectory.lastIndexOf('/') + 1) :
                "";
    }

    public @NotNull List<String> getDropboxDirectoryList() {
        var output = new LinkedList<>(List.of(dropboxDirectory.split("/")));
        output.removeFirst();
        if (isFile()) {
            output.removeLast();
        }
        return output;
    }

    @Contract(pure = true)
    public boolean haveSameSubdirectory(@NonNull DbxLongListFileInfo fileInfo) {
        return this.noteFileSubdirectory.equals(fileInfo.noteFileSubdirectory);
    }

    @Override
    public String toString() {
        return dropboxDirectory;
    }

    public static int compareByFilename(DbxLongListFileInfo l, DbxLongListFileInfo r) {
        var leftDirectory = l.dropboxDirectory;
        var rightDirectory = r.dropboxDirectory;

        var leftFilename = leftDirectory.substring(leftDirectory.lastIndexOf('/'));
        var rightFilename = rightDirectory.substring(rightDirectory.lastIndexOf('/'));

        return leftFilename.compareTo(rightFilename);
    }

    @Contract(pure = true)
    public static int compareByAge(DbxLongListFileInfo l, DbxLongListFileInfo r) {
        if (l.modificationAgeUnit == null && r.modificationAgeUnit == null) {
            return 0;
        } else if (l.modificationAgeUnit == null) {
            return -1;
        } else if (r.modificationAgeUnit == null) {
            return 1;
        }
        int unitComparison = l.modificationAgeUnit.compareTo(r.modificationAgeUnit);

        return unitComparison == 0 ?
                Integer.compare(l.modificationAge, r.modificationAge) :
                unitComparison;
    }

    @Contract(pure = true)
    public static int compareByFileSize(DbxLongListFileInfo l, DbxLongListFileInfo r) {
        if (l.fileSizePrefix == null && r.fileSizePrefix == null) {
            return 0;
        } else if (l.fileSizePrefix == null) {
            return -1;
        } else if (r.fileSizePrefix == null) {
            return 1;
        }
        return l.fileSizePrefix == r.fileSizePrefix ?
                Double.compare(l.fileSize, r.fileSize) :
                l.fileSizePrefix.compareTo(r.fileSizePrefix);
    }

    @Contract(pure = true)
    public static int compareByFileSizeSubByAge(DbxLongListFileInfo l, DbxLongListFileInfo r) {
        int value = compareByFileSize(l, r);
        return value == 0 ?
                compareByAge(l, r) :
                value;
    }

    @RequiredArgsConstructor
    public enum FileSizeType {
        KIBIBYTES("KiB"), MEBIBYTES("MiB");

        @Getter
        private final String abbreviation;
    }
}
