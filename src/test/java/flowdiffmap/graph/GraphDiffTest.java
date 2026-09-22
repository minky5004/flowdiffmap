package flowdiffmap.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class GraphDiffTest {

    static Node node(String id, String hash) {
        return new Node(id, "shop.X", id, Layer.SERVICE, null, hash, "shop/X.java");
    }

    static Edge edge(String from, String to) {
        return new Edge(from, to, "shop/X.java");
    }

    static Graph graph(Set<Edge> edges, Node... nodes) {
        return new Graph(Stream.of(nodes).collect(Collectors.toMap(Node::id, Function.identity())), edges);
    }

    @Test
    void 노드_추가_삭제_변경() {
        Graph before = graph(Set.of(), node("a", "1"), node("b", "1"), node("c", "1"));
        Graph after = graph(Set.of(), node("a", "1"), node("b", "2"), node("d", "1"));

        GraphDiff d = GraphDiff.between(before, after);

        assertThat(d.addedNodes()).containsExactly("d");
        assertThat(d.removedNodes()).containsExactly("c");
        assertThat(d.changedNodes()).containsExactly("b");
    }

    @Test
    void 엣지_추가_삭제() {
        Graph before = graph(Set.of(edge("a", "b"), edge("a", "c")), node("a", "1"), node("b", "1"), node("c", "1"));
        Graph after = graph(Set.of(edge("a", "b"), edge("b", "c")), node("a", "1"), node("b", "1"), node("c", "1"));

        GraphDiff d = GraphDiff.between(before, after);

        assertThat(d.addedEdges()).containsExactly(edge("b", "c"));
        assertThat(d.removedEdges()).containsExactly(edge("a", "c"));
        assertThat(d.addedNodes()).isEmpty();
        assertThat(d.changedNodes()).isEmpty();
    }

    @Test
    void 같은_그래프면_빈_diff() {
        Graph g = graph(Set.of(edge("a", "b")), node("a", "1"), node("b", "1"));

        assertThat(GraphDiff.between(g, graph(g.edges(), node("a", "1"), node("b", "1"))).isEmpty()).isTrue();
    }

    @Test
    void 부모_스냅샷_없으면_빈_diff() {
        Graph after = graph(Set.of(edge("a", "b")), node("a", "1"), node("b", "1"));

        assertThat(GraphDiff.between(null, after).isEmpty()).isTrue();
    }
}
