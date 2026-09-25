package flowdiffmap.render;

import static org.assertj.core.api.Assertions.assertThat;

import flowdiffmap.graph.Edge;
import flowdiffmap.graph.Graph;
import flowdiffmap.graph.Layer;
import flowdiffmap.graph.Node;
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

    static String render(Graph before, Graph after) {
        return MermaidRenderer.render(before, after, "abc1234");
    }

    @Test
    void 추가_노드에_added() {
        Graph before = graph(Set.of(), GET, FIND);
        Graph after = graph(Set.of(), GET, FIND, CANCEL);

        String md = render(before, after);

        assertThat(md).contains("shop_OrderService_cancel_1[\"OrderService.cancel\"]:::added")
                .contains("shop_OrderController_get_1[\"GET /orders/{id}<br/>OrderController.get\"]\n")
                .contains("| 추가 | OrderService.cancel |");
    }

    @Test
    void 변경_노드에_changed() {
        Graph before = graph(Set.of(), FIND);
        Graph after = graph(Set.of(), service("find", 1, "2"));

        assertThat(render(before, after)).contains("[\"OrderService.find\"]:::changed")
                .contains("| 변경 | OrderService.find |");
    }

    @Test
    void 삭제_노드는_부모_그래프에서_가져와_removed() {
        Graph before = graph(Set.of(), GET, FIND, CANCEL);
        Graph after = graph(Set.of(), GET, FIND);

        String md = render(before, after);

        assertThat(md).contains("[\"OrderService.cancel\"]:::removed")
                .contains("| 삭제 | OrderService.cancel |");
    }

    @Test
    void 새_엔드포인트_전체는_기능_추가로_묶고_기존_노드_호출은_개별_행() {
        Node cancelController = new Node("shop.OrderController#cancel/1", "shop.OrderController", "cancel",
                Layer.CONTROLLER, "DELETE /orders/{id}", "1", "shop/OrderController.java");
        Graph before = graph(Set.of(edge(FIND, BY_ID)), FIND, BY_ID);
        Graph after = graph(Set.of(edge(FIND, BY_ID), edge(cancelController, CANCEL), edge(CANCEL, BY_ID)),
                FIND, BY_ID, cancelController, CANCEL);

        String md = render(before, after);

        assertThat(md).contains("| 기능 추가 | DELETE /orders/{id} |")
                .doesNotContain("| 추가 | OrderController.cancel |")
                .doesNotContain("| 추가 | OrderService.cancel |")
                .contains("| 호출 추가 | OrderService.cancel → OrderRepository.findById |");
    }

    @Test
    void 기존_흐름에_메서드만_추가되면_rollup_없이_개별_행() {
        Graph before = graph(Set.of(), GET, FIND);
        Graph after = graph(Set.of(), GET, FIND, CANCEL);

        String md = render(before, after);

        assertThat(md).contains("| 추가 | OrderService.cancel |").doesNotContain("기능 추가");
    }

    @Test
    void 다른_엔드포인트를_직접_부르는_새_엔드포인트도_각각_기능_행으로_남음() {
        Node forceCancel = new Node("shop.AdminController#forceCancel/1", "shop.AdminController", "forceCancel",
                Layer.CONTROLLER, "DELETE /admin/orders/{id}", "1", "shop/AdminController.java");
        Node cancelController = new Node("shop.OrderController#cancel/1", "shop.OrderController", "cancel",
                Layer.CONTROLLER, "DELETE /orders/{id}", "1", "shop/OrderController.java");
        Graph before = graph(Set.of());
        Graph after = graph(Set.of(edge(forceCancel, cancelController)), forceCancel, cancelController);

        String md = render(before, after);

        assertThat(md).contains("| 기능 추가 | DELETE /admin/orders/{id} |")
                .contains("| 기능 추가 | DELETE /orders/{id} |")
                .contains("| 호출 추가 | AdminController.forceCancel → OrderController.cancel |");
    }

    @Test
    void 새_메서드를_두_흐름이_동시에_부르면_공유_노드는_개별_행으로_남음() {
        Node cancelController = new Node("shop.OrderController#cancel/1", "shop.OrderController", "cancel",
                Layer.CONTROLLER, "DELETE /orders/{id}", "1", "shop/OrderController.java");
        Node validate = service("validate", 1, "1");
        Graph before = graph(Set.of(), GET);
        Graph after = graph(Set.of(edge(cancelController, validate), edge(GET, validate)),
                GET, cancelController, validate);

        String md = render(before, after);

        assertThat(md).contains("| 기능 추가 | DELETE /orders/{id} |")
                .contains("| 추가 | OrderService.validate |")
                .contains("| 호출 추가 | OrderController.cancel → OrderService.validate |")
                .contains("| 호출 추가 | OrderController.get → OrderService.validate |");
    }

    @Test
    void 삭제된_엔드포인트_전체는_기능_삭제로_묶음() {
        Graph before = graph(Set.of(edge(GET, CANCEL)), GET, CANCEL);
        Graph after = graph(Set.of());

        String md = render(before, after);

        assertThat(md).contains("| 기능 삭제 | GET /orders/{id} |")
                .doesNotContain("| 삭제 | OrderController.get |")
                .doesNotContain("| 삭제 | OrderService.cancel |")
                .doesNotContain("| 호출 삭제 |");
    }

    @Test
    void 추가_엣지는_초록_삭제_엣지는_빨간_linkStyle() {
        Graph before = graph(Set.of(edge(GET, CANCEL)), GET, FIND, CANCEL);
        Graph after = graph(Set.of(edge(GET, FIND)), GET, FIND, CANCEL);

        String md = render(before, after);

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

        String md = render(before, after);

        assertThat(md).contains("[\"OrderRepository.findById\"]\n")
                .doesNotContain("| 추가 | OrderRepository.findById |")
                .contains("| 호출 추가 | OrderService.find → OrderRepository.findById |");
    }

    @Test
    void 오버로드만_이름에_인자_수() {
        Graph g = graph(Set.of(), FIND, service("find", 2, "1"), CANCEL);

        assertThat(render(g, g)).contains("[\"OrderService.find/1\"]").contains("[\"OrderService.find/2\"]")
                .contains("[\"OrderService.cancel\"]");
    }

    @Test
    void 엔드포인트_속_따옴표_꺾쇠_이스케이프() {
        Node odd = new Node("shop.OrderController#odd/0", "shop.OrderController", "odd",
                Layer.CONTROLLER, "GET /a\"b\"/<c>", "1", "shop/OrderController.java");

        String md = render(graph(Set.of()), graph(Set.of(), odd));

        assertThat(md).contains("[\"GET /a#quot;b#quot;/#lt;c#gt;<br/>OrderController.odd\"]:::added");
    }

    @Test
    void 빈_diff_면_classDef_없음() {
        Graph g = graph(Set.of(edge(GET, FIND)), GET, FIND);

        String md = render(g, g);

        assertThat(md).doesNotContain("classDef").doesNotContain(":::").doesNotContain("linkStyle")
                .contains("바뀐 흐름 없음");
    }

    @Test
    void 부모_없으면_하이라이트_없이_전체() {
        Graph after = graph(Set.of(edge(GET, FIND)), GET, FIND);

        String md = render(null, after);

        assertThat(md).contains("shop_OrderController_get_1 --> shop_OrderService_find_1")
                .doesNotContain(":::").contains("첫 스냅샷");
    }
}
