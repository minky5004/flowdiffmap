package flowdiffmap.graph;

import java.util.Map;
import java.util.Set;

public record Graph(Map<String, Node> nodes, Set<Edge> edges) {
}
