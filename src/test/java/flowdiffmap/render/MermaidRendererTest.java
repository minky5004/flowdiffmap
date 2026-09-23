package flowdiffmap.render;

import static org.assertj.core.api.Assertions.assertThat;

import flowdiffmap.graph.Edge;
import flowdiffmap.graph.Graph;
import flowdiffmap.graph.GraphDiff;
import flowdiffmap.graph.Layer;
import flowdiffmap.graph.Node;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MermaidRendererTest {

    static final Node GET = new Node("shop.OrderController#get/1", "shop.OrderController", "get",
            Layer.CONTROLLER, "GET /orders/{id}", "1", "shop/OrderController.java");
    static final Node FIND = new Node("shop.OrderService#find/1", "shop.OrderService", "find",
            Layer.SERVICE, null, "1", "shop/OrderService.java");
    static final Node CANCEL = new Node("shop.OrderService#cancel/1", "shop.OrderService", "cancel",
            Layer.SERVICE, null, "1", "shop/OrderService.java");
    static final Node BY_ID = new Node("shop.OrderRepository#findById/1", "shop.OrderRepository", "findById",
            Layer.REPOSITORY, null, "", "shop/OrderRepository.java");

    static Graph graph(Set<Edge> edges, Node... nodes) {
        return new Graph(Stream.of(nodes).collect(Collectors.toMap(Node::id, Function.identity())), edges, Set.of());
    }

    static Edge edge(Node from, Node to) {
        return new Edge(from.id(), to.id(), from.file());
    }

    static String render(Graph before, Graph after, Map<String, String> labels) {
        return MermaidRenderer.render(before, after, GraphDiff.between(before, after), labels, "abc1234");
    }

    @Test
    void 추가_노드에_added() {
        Graph before = graph(Set.of(edge(GET, FIND)), GET, FIND);
        Graph after = graph(Set.of(edge(GET, FIND), edge(FIND, BY_ID)), GET, FIND, BY_ID);

        String md = render(before, after, Map.of());

        assertThat(md).contains("[\"OrderRepository.findById\"]:::added")
                .contains("[\"GET /orders/{id}<br/>OrderController.get\"]\n");
    }

    @Test
    void 삭제_노드는_부모_그래프에서_가져와_removed() {
        Graph before = graph(Set.of(), GET, FIND, CANCEL);
        Graph after = graph(Set.of(), GET, FIND);

        String md = render(before, after, Map.of(CANCEL.id(), "주문 취소"));

        assertThat(md).contains("[\"주문 취소\"]:::removed")
                .contains("| 삭제 | 주문 취소 |");
    }

    @Test
    void 삭제_엣지는_빨간_linkStyle() {
        Graph before = graph(Set.of(edge(GET, FIND), edge(GET, CANCEL)), GET, FIND, CANCEL);
        Graph after = graph(Set.of(edge(GET, FIND)), GET, FIND, CANCEL);

        String md = render(before, after, Map.of());

        // 엣지는 id 순 — cancel 이 find 보다 앞이라 삭제된 cancel 호출이 0번
        assertThat(md).contains("n0 --> n1\n    n0 --> n2")
                .contains("linkStyle 0 stroke:#d33")
                .doesNotContain("linkStyle 1");
    }

    @Test
    void 라벨_속_따옴표_이스케이프() {
        Graph g = graph(Set.of(), FIND);

        String md = render(g, g, Map.of(FIND.id(), "\"VIP\" 주문 조회"));

        assertThat(md).contains("[\"#quot;VIP#quot; 주문 조회\"]");
    }

    @Test
    void 빈_diff_면_classDef_없음() {
        Graph g = graph(Set.of(edge(GET, FIND)), GET, FIND);

        String md = render(g, g, Map.of());

        assertThat(md).doesNotContain("classDef").doesNotContain(":::").doesNotContain("linkStyle")
                .contains("바뀐 흐름 없음");
    }

    @Test
    void 부모_없으면_하이라이트_없이_전체() {
        Graph after = graph(Set.of(edge(GET, FIND)), GET, FIND);

        String md = render(null, after, Map.of());

        assertThat(md).contains("n0 --> n1").doesNotContain(":::");
    }
}
