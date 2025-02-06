package jasmine.jragon.dropbox.model.v2.movement.advanced;

import jasmine.jragon.tuple.type.Duo;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The {@code GraphicsCluster} captures all the content data of a single page of a PDF document that's retrieved
 * from Boox. A {@linkplain #$clusterHash custom hash} is <u>calculated</u> based on solely <b>the content of the nodes
 * inside the cluster</b> because we're trying to establish whether 2 documents are the same or similar based on
 * the content of their pages.
 */
@EqualsAndHashCode(
        doNotUseGetters = true,
        of = {"pageNumber", "graphicsNodes"},
        cacheStrategy = EqualsAndHashCode.CacheStrategy.LAZY
)
final class GraphicsCluster {
    private final Set<GraphicsCluster> incomingNodes;
    private final Set<GraphicsCluster> outgoingNodes;
    private final Set<PageCitation> foundPaths;

    //Used to compute object equality and hashcode
    private final int pageNumber;
    private final List<GraphicsNode> graphicsNodes;

    @Getter
    private transient final int $clusterHash;

    GraphicsCluster(@NonNull String absolutePath, int pageNumber, @NonNull List<PDAnnotation> annotations)
            throws IllegalStateException {
        this.pageNumber = pageNumber;
        graphicsNodes = extractNodes(annotations);
        $clusterHash = graphicsNodes.isEmpty() ? 0 : graphicsNodes.hashCode();

        incomingNodes = new HashSet<>(5, 1.0f);
        outgoingNodes = new HashSet<>(5, 1.0f);
        foundPaths = new TreeSet<>();
        foundPaths.add(new PageCitation(
                absolutePath, pageNumber,
                findLatestModTime(graphicsNodes)
        ));
    }

    private static List<GraphicsNode> extractNodes(List<PDAnnotation> annotations) {
        //When the page has no annotations, that means nothing is drawn on the page
        if (annotations.isEmpty()) {
            return Collections.emptyList();
        }

        return annotations.stream()
                .map(annotation -> Duo.of(navigateToBBox(annotation), annotation.getModifiedDate()))
                .map(duo -> GraphicsNode.create(duo.first(), duo.second()))
                .toList();
    }

    private static LocalDateTime findLatestModTime(List<GraphicsNode> graphicsNodes) {
        return graphicsNodes.stream()
                .map(graphicsNode -> graphicsNode.modTime)
                .max(LocalDateTime::compareTo)
                .orElse(GraphicsNode.BLANK_MOD_TIME);
    }

    private static COSStream navigateToBBox(PDAnnotation annotation) {
        return annotation.getAppearance()
                .getCOSObject()
                .getCOSStream(COSName.N);
    }

    private void addIncomingNode(GraphicsCluster node) {
        incomingNodes.add(node);
    }

    public void linkClusters(@NonNull GraphicsCluster node) {
        outgoingNodes.add(node);
        node.addIncomingNode(this);
    }

    public void addNewPathsFromCluster(@NonNull GraphicsCluster cluster) {
        foundPaths.addAll(cluster.foundPaths);
    }

    public boolean isStartingNode() {
        return incomingNodes.isEmpty();
    }

    public boolean hasEmptyClusterHash() {
        return $clusterHash == 0;
    }

    @Contract(pure = true)
    public @NotNull @UnmodifiableView Set<GraphicsCluster> getOutgoingNodes() {
        return Collections.unmodifiableSet(outgoingNodes);
    }

    public boolean hasMultipleCitations() {
        return foundPaths.size() > 1;
    }

    @Override
    public @NotNull String toString() {
        if ($clusterHash == 0) {
            return String.format("Path(s) %s : Page %d\tEMPTY", foundPaths, pageNumber);
        } else {
            return String.format("Path(s) %s : Page %d\tNode Count: %d", foundPaths, pageNumber, graphicsNodes.size());
        }
    }

    /**
     * A {@code GraphicsNode} represents the important information from one annotation from a Boox Note PDF.
     * The important content involve the {@linkplain #compressedStream stream data from the PDF BBox}
     * that the annotation points to, the {@linkplain #modTime modification time} the BBox was last updated,
     * and the {@linkplain #location location of the BBox} specified as a float array.
     * All but the modTime are used to calculate the {@linkplain #hashCode() hashcode}  of a {@code GraphicsNode}
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    @EqualsAndHashCode(
            of = {"compressedStream", "location"},
            doNotUseGetters = true,
            cacheStrategy = EqualsAndHashCode.CacheStrategy.LAZY
    )
    static final class GraphicsNode {
        private static final DateTimeFormatter NOTE_FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
                TO_STRING_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss");
        //A time before normalcy was ripped away from us XD
        private static final LocalDateTime BLANK_MOD_TIME = LocalDateTime.of(
                2020, 1, 1,
                0, 0, 0
        );

        @NonNull
        private final String compressedStream;
        private final float @NonNull [] location;
        @NonNull
        private final LocalDateTime modTime;

        private static GraphicsNode create(@NonNull COSStream bBoxStream, @Nullable String modString) {
            String rawContent;
            try (var rawStream = bBoxStream.createRawInputStream();
                 var bufferedReader = new BufferedReader(new InputStreamReader(rawStream))) {
                rawContent = bufferedReader
                        .lines()
                        .collect(Collectors.joining());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }

            return new GraphicsNode(
                    rawContent,
                    bBoxStream.getCOSArray(COSName.BBOX).toFloatArray(),
                    convertModificationTime(modString)
            );
        }

        private static LocalDateTime convertModificationTime(String modString) {
            return modString != null && modString.matches("D:\\d{14}") ?
                    LocalDateTime.parse(modString.substring(2), NOTE_FILE_TIME_FORMAT) :
                    BLANK_MOD_TIME;
        }

        @Override
        public @NotNull String toString() {
            return String.format("Location %s - %s",
                    Arrays.toString(location),
                    modTime.format(TO_STRING_FORMAT)
            );
        }
    }

    /**
     * The {@code PageCitation} is a compact place to hold page information to compare to pages of other documents
     * that contain the exact same content.
     */
    @ToString
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    @EqualsAndHashCode(doNotUseGetters = true, cacheStrategy = EqualsAndHashCode.CacheStrategy.LAZY)
    static final class PageCitation implements Comparable<PageCitation> {
        private final String absolutePath;
        private final int pageNumber;
        private final LocalDateTime modTime;

        @Override
        public int compareTo(@NotNull GraphicsCluster.PageCitation o) {
            return this.modTime.compareTo(o.modTime);
        }
    }
}
