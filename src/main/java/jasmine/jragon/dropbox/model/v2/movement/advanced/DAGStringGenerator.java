package jasmine.jragon.dropbox.model.v2.movement.advanced;

import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

final class DAGStringGenerator {
    static @NotNull String generateGraphString(@NonNull GraphicsCluster startNode) {
        var result = new StringBuilder();
        Set<GraphicsCluster> visited = new HashSet<>();
        dfs(startNode, visited, result);
        return result.toString();
    }

    private static void dfs(GraphicsCluster node, Set<GraphicsCluster> visited, StringBuilder result) {
        if (visited.contains(node)) return;
        visited.add(node);

        for (GraphicsCluster next : node.getOutgoingNodes()) {
            result.append(node.getClusterHash())
                    .append(" -> ")
                    .append(next.getClusterHash())
                    .append("\n");
            dfs(next, visited, result);
        }
    }
}
