package jasmine.jragon.dropbox.model.v2.movement.simple;

import jasmine.jragon.dropbox.model.v2.DbxLongListFileInfo;
import jasmine.jragon.dropbox.model.v2.IntermediateFile;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RequiredArgsConstructor
public final class FileMove {
    private static final Set<String> BASE_FILE_DIRECTORY_FOLDERS;

    private transient int $hashcode = 0;
    private final DbxLongListFileInfo firstFile, secondFile;

    static {
        //Fetches the base paths from the Intermediate File class
        //All of them are marked as static final with no access modifier

        BASE_FILE_DIRECTORY_FOLDERS = Stream.of(IntermediateFile.class)
                .map(Class::getDeclaredFields)
                .flatMap(Arrays::stream)
                .filter(FileMove::isPackageAccessStringConstant)
                .peek(field -> field.setAccessible(true))
                .map(FileMove::fetchValue)
                .filter(Objects::nonNull)
                .map(o -> (String)o)
                .map(path -> path.substring(1))
                .map(path -> path.split("/"))
                .flatMap(Arrays::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static boolean isPackageAccessStringConstant(Field field) {
        int modifiers = field.getModifiers();
        int notPackageLevelAccess = Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED;
        int classConstant = Modifier.FINAL | Modifier.STATIC;
        return (modifiers & notPackageLevelAccess) == 0 && (modifiers & classConstant) == classConstant && field.getType() == String.class;
    }

    public static Object fetchValue(Field field) {
        try {
            return field.get(null);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    public boolean isValidMove() {
        if (firstFile.equals(secondFile)) {
            return false;
        }

        var firstDirectoryList = firstFile.getDropboxDirectoryList();
        var secondDirectoryList = secondFile.getDropboxDirectoryList();
        var firstDirectorClone = new ArrayList<>(firstDirectoryList);
        var secondDirectoryClone = new ArrayList<>(secondDirectoryList);
        firstDirectorClone.removeAll(BASE_FILE_DIRECTORY_FOLDERS);
        secondDirectoryClone.removeAll(BASE_FILE_DIRECTORY_FOLDERS);
        firstDirectorClone.removeAll(secondDirectoryList);
        secondDirectoryClone.removeAll(firstDirectoryList);

        if ((firstDirectorClone.size() == 1 && secondDirectoryClone.isEmpty()) ||
                (firstDirectorClone.isEmpty() && secondDirectoryClone.size() == 1)) {
            return true;
        } else if (firstDirectorClone.size() == 1 && secondDirectoryClone.size() == 1) {
            //This signifies a 'move' where a directory was renamed somewhere along the absolute path 'chain'
            var firstDiff = firstDirectorClone.get(0);
            var secondDiff = secondDirectoryClone.get(0);

            //One diff MUST contain the other diff as a substring of the new directory
            //This is enforced as to not confuse
            if (!firstDiff.toLowerCase().contains(secondDiff.toLowerCase()) &&
                    !secondDiff.toLowerCase().contains(firstDiff.toLowerCase())) {
                return false;
            }

            //This shows that the different directories are at the same level
            return firstDirectoryList.size() == secondDirectoryList.size() &&
                    firstDirectoryList.indexOf(firstDiff) == secondDirectoryList.indexOf(secondDiff);
        }

        return firstFile.haveSameSubdirectory(secondFile);
    }

    public Optional<DbxLongListFileInfo> getOlderFile() {
        int ageComparison = DbxLongListFileInfo.compareByAge(firstFile, secondFile);

        if (ageComparison != 0) {
            var result = ageComparison > 0 ?
                    firstFile :
                    secondFile;
            return Optional.of(result);
        }

        int sizeComparison = DbxLongListFileInfo.compareByFileSize(firstFile, secondFile);
        //Working under the assumption that larger file sizes mean newer files
        if (sizeComparison != 0) {
            var result = sizeComparison < 0 ?
                    firstFile :
                    secondFile;
            return Optional.of(result);
        }
        //Empty meaning we cannot determine the which is the older file without peering into the exact file size
        return Optional.empty();
    }

    @Override
    public @NotNull String toString() {
        if (!isValidMove()) {
            return "Invalid file move";
        }
        var olderFile = getOlderFile();
        var firstSubDir = firstFile.getNoteFileSubdirectory();
        var secondSubDir = secondFile.getNoteFileSubdirectory();
        if (olderFile.isEmpty()) {
            return String.format("Older file undetermined: %s <-> %s", firstFile, secondFile);
        } else if (firstSubDir.equals(secondSubDir)) {
            return "Move to new directory: " + firstSubDir;
        }

        boolean isFirstFileOlder = olderFile.get() == firstFile;
        var olderFileDir = isFirstFileOlder ? firstSubDir : secondSubDir;
        var youngerFileDir = isFirstFileOlder ? secondSubDir : firstSubDir;
        return String.format("%s -> %s", olderFileDir, youngerFileDir);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (o instanceof FileMove f) {
            return f.firstFile.equals(this.firstFile) && f.secondFile.equals(this.secondFile) ||
                    f.secondFile.equals(this.firstFile) && f.firstFile.equals(this.secondFile);
        }
        return false;
    }

    @Override
    public int hashCode() {
        if ($hashcode == 0) {
            $hashcode = firstFile.hashCode() + secondFile.hashCode();
        }
        return $hashcode;
    }
}
