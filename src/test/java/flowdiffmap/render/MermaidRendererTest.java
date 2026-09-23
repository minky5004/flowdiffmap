package flowdiffmap.render;

import static org.assertj.core.api.Assertions.assertThat;

import flowdiffmap.graph.Edge;
import flowdiffmap.graph.Graph;
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
    static final Node FIND = service("find", 1, "1");
    static final Node CANCEL = service("cancel", 1, "1");
    /** 상속 메서드 — {@code GraphStore.load} 가 채우는 암묵 노드라 bodyHash 가 빈 문자열. */
    static final Node BY_ID = new Node("shop.OrderRepository#findById/1", "shop.OrderRepository", "findById",
            Layer.REPOSITORY, null, "", "shop/OrderRepository.java");

    static Node service(String method, int arity, String hash) {
        return new Node("shop.OrderService#" + method + "/" + arity, "shop.OrderService", method,
                Layer.SERVICE, null, hash, "shop/OrderService.java");
    }

    static Graph graph(Set<Edge> edges, Node... nodes) {
        return new Graph(Stream.of(nodes).collect(Collectors.toMap(Node::id, Function.identity())), edges, Set.of());
    }

    static Edge edge(Node from, Node to) {
        return new Edge(from.id(), to.id(), from.file());
    }

    static String render(Graph before, Graph after, Map<String, String> labels) {
        return MermaidRenderer.render(before, after, labels, "abc1234");
    }

    @Test
    void 추가_노드에_added() {
        Graph before = graph(Set.of(), GET, FIND);
        Graph after = graph(Set.of(), GET, FIND, CANCEL);

        String md = render(before, after, Map.of());

        assertThat(md).contains("shop_OrderService_cancel_1[\"OrderService.cancel\"]:::added")
                .contains("shop_OrderController_get_1[\"GET /orders/{id}<br/>OrderController.get\"]\n")
                .contains("| 추가 | OrderService.cancel |");
    }

    @Test
    void 변경_노드에_changed() {
        Graph before = graph(Set.of(), FIND);
        Graph after = graph(Set.of(), service("find", 1, "2"));

        assertThat(render(before, after, Map.of())).contains("[\"OrderService.find\"]:::changed")
                .contains("| 변경 | OrderService.find |");
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
    void 추가_엣지는_초록_삭제_엣지는_빨간_linkStyle() {
        Graph before = graph(Set.of(edge(GET, CANCEL)), GET, FIND, CANCEL);
        Graph after = graph(Set.of(edge(GET, FIND)), GET, FIND, CANCEL);

        String md = render(before, after, Map.of());

        // 엣지는 id 순 — cancel 이 find 보다 앞이라 삭제된 cancel 호출이 0번
        assertThat(md).contains("    shop_OrderController_get_1 --> shop_OrderService_cancel_1\n"
                        + "    shop_OrderController_get_1 --> shop_OrderService_find_1\n")
                .contains("linkStyle 0 stroke:#d33")
                .contains("linkStyle 1 stroke:#2a2")
                .contains("| 호출 추가 | OrderController.get → OrderService.find |");
    }

    @Test
    void 암묵_노드는_호출이_생겨도_칠하지_않음() {
        Graph before = graph(Set.of(), FIND);
        Graph after = graph(Set.of(edge(FIND, BY_ID)), FIND, BY_ID);

        String md = render(before, after, Map.of());

        assertThat(md).contains("[\"OrderRepository.findById\"]\n")
                .doesNotContain("| 추가 | OrderRepository.findById |")
                .contains("| 호출 추가 | OrderService.find → OrderRepository.findById |");
    }

    @Test
    void 오버로드만_폴백_이름에_인자_수() {
        Graph g = graph(Set.of(), FIND, service("find", 2, "1"), CANCEL);

        assertThat(render(g, g, Map.of())).contains("[\"OrderService.find/1\"]").contains("[\"OrderService.find/2\"]")
                .contains("[\"OrderService.cancel\"]");
    }

    @Test
    void 라벨_속_따옴표_꺾쇠_줄바꿈_이스케이프() {
        Graph before = graph(Set.of(), GET);
        Graph after = graph(Set.of(), GET, FIND);

        String md = render(before, after, Map.of(FIND.id(), "\"VIP\" List<Order>\n조회 | 단건"));

        assertThat(md).contains("[\"#quot;VIP#quot; List#lt;Order#gt; 조회 | 단건\"]")
                .contains("| 추가 | \"VIP\" List&lt;Order> 조회 \\| 단건 |");
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

        assertThat(md).contains("shop_OrderController_get_1 --> shop_OrderService_find_1")
                .doesNotContain(":::").contains("첫 스냅샷");
    }
}
