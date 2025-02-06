package jasmine.jragon.dropbox.model.v2.movement.advanced;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDPageTree;
import speiger.src.collections.ints.maps.impl.concurrent.Int2ObjectConcurrentOpenHashMap;
import speiger.src.collections.ints.maps.impl.customHash.Int2ObjectLinkedOpenCustomHashMap;
import speiger.src.collections.ints.maps.interfaces.Int2ObjectMap;
import speiger.src.collections.ints.utils.IntStrategy;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

@Slf4j
public final class PageContentIndex {
    private static final int ESTIMATED_PAGE_COUNT = 2500;
    private static final float LOAD_FACTOR = 0.99f;

    private final Int2ObjectMap<GraphicsCluster> indexMap;
    private final List<GraphicsCluster> emptyClusters;

    public PageContentIndex() {
        indexMap = new Int2ObjectLinkedOpenCustomHashMap<>(ESTIMATED_PAGE_COUNT, LOAD_FACTOR, IntStrategy.NORMAL);
        emptyClusters = new ArrayList<>();
    }

    public PageContentIndex(boolean isConcurrent) {
        indexMap = isConcurrent ?
                new Int2ObjectConcurrentOpenHashMap<>(ESTIMATED_PAGE_COUNT, LOAD_FACTOR) :
                new Int2ObjectLinkedOpenCustomHashMap<>(ESTIMATED_PAGE_COUNT, LOAD_FACTOR, IntStrategy.NORMAL);

        emptyClusters = isConcurrent ?
                Collections.synchronizedList(new ArrayList<>()) :
                new ArrayList<>();
    }

    public void addDocument(@NonNull String absolutePath, @NonNull PDPageTree pages)
            throws IllegalArgumentException {
        if (pages.getCount() == 0) {
            throw new IllegalArgumentException("PDFs with no pages are not allowed");
        } else if (absolutePath.isBlank()) {
            throw new IllegalArgumentException("Must specify absolute path");
        }

        int pageNumber = 1;
        List<GraphicsCluster> successfulClusters = new ArrayList<>();
        for (var page : pages) {
            try {
                var cluster = new GraphicsCluster(absolutePath, pageNumber, page.getAnnotations());
                successfulClusters.add(cluster);
            } catch (IOException e) {
                log.warn("{} Page {} Issue: {}", absolutePath, pageNumber, e.getMessage());
                return;
            } catch (IllegalStateException e) {
                log.error("{} Page {} Node Generation Issue: {}", absolutePath, pageNumber, e.getMessage());
                return;
            }
            pageNumber++;
        }

        GraphicsCluster previousCluster = null;

        for (var cluster : successfulClusters) {
            int hash = cluster.get$clusterHash();

            if (cluster.hasEmptyClusterHash()) {
                emptyClusters.add(cluster);
            } else if (!indexMap.containsKey(hash)) {
                if (previousCluster != null) {
                    previousCluster.linkClusters(cluster);
                }

                indexMap.put(hash, cluster);
                previousCluster = cluster;
            } else {
                //Same hash means that the page content is the same
                var insertedCluster = indexMap.get(hash);

                if (previousCluster != null) {
                    previousCluster.linkClusters(insertedCluster);
                }
                insertedCluster.addNewPathsFromCluster(cluster);
                previousCluster = insertedCluster;
            }
        }
    }

    public void traceDuplicateIndexes() {
        var startingNodes = indexMap.values()
                .stream()
                .filter(GraphicsCluster::isStartingNode)
                .filter(PageContentIndex::documentHasMultipleCitations)
                .toList();

        log.debug("Remaining Clusters: {}", startingNodes);
    }

    private static boolean documentHasMultipleCitations(@NonNull GraphicsCluster cluster) {
        Queue<GraphicsCluster> queue = new ArrayDeque<>();
        queue.add(cluster);
        while (!queue.isEmpty()) {
            var next = queue.poll();
            if (next.hasMultipleCitations()) {
                return true;
            }

            queue.addAll(next.getOutgoingNodes());
        }

        return false;
    }
}
