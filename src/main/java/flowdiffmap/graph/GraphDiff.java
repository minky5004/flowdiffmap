package flowdiffmap.graph;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 부모 커밋 스냅샷 대비 바뀐 노드 id 와 엣지.
 *
 * @param changedNodes 양쪽에 다 있고 {@code bodyHash} 만 다른 노드
 */
public record GraphDiff(Set<String> addedNodes, Set<String> removedNodes, Set<String> changedNodes,
                        Set<Edge> addedEdges, Set<Edge> removedEdges) {

    static final GraphDiff EMPTY = new GraphDiff(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

    /** {@code before} 가 null 이면 베이스라인 — 하이라이트할 것이 없어 빈 diff. */
    public static GraphDiff between(Graph before, Graph after) {
        if (before == null) {
            return EMPTY;
        }
        Map<String, Node> b = before.nodes(), a = after.nodes();
        Set<String> changed = a.keySet().stream()
                .filter(id -> b.containsKey(id) && !b.get(id).bodyHash().equals(a.get(id).bodyHash()))
                .collect(Collectors.toSet());
        return new GraphDiff(minus(a.keySet(), b.keySet()), minus(b.keySet(), a.keySet()), changed,
                minus(after.edges(), before.edges()), minus(before.edges(), after.edges()));
    }

    public boolean isEmpty() {
        return addedNodes.isEmpty() && removedNodes.isEmpty() && changedNodes.isEmpty()
                && addedEdges.isEmpty() && removedEdges.isEmpty();
    }

    private static <T> Set<T> minus(Set<T> x, Set<T> y) {
        Set<T> r = new HashSet<>(x);
        r.removeAll(y);
        return r;
    }
}
