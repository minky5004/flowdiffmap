package flowdiffmap.render;

import static org.assertj.core.api.Assertions.assertThat;

import flowdiffmap.graph.Edge;
import flowdiffmap.graph.Graph;
import flowdiffmap.graph.Layer;
import flowdiffmap.graph.Node;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MermaidRendererTest {

    static final Node GET = new Node("shop.OrderController#get(Long)", "shop.OrderController", "get",
            Layer.CONTROLLER, "GET /orders/{id}", "1", "shop/OrderController.java");
    static final Node FIND = service("find", 1, "1");
    static final Node CANCEL = service("cancel", 1, "1");
    /** 상속 메서드 — {@code GraphStore.load} 가 채우는 암묵 노드라 bodyHash 가 빈 문자열. */
    static final Node BY_ID = new Node("shop.OrderRepository#findById(Long)", "shop.OrderRepository", "findById",
            Layer.REPOSITORY, null, "", "shop/OrderRepository.java");

    /** 파라미터는 인자 수만큼의 {@code Long} — 타입이 다른 오버로드가 필요한 테스트는 노드를 직접 짓는다. */
    static Node service(String method, int arity, String hash) {
        return new Node("shop.OrderService#" + method + "(" + String.join(",", Collections.nCopies(arity, "Long")) + ")",
                "shop.OrderService", method,
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
        // 기존 get 이 새 cancel 을 부름 — 변경에 이어진 기존 노드는 색 없이 그대로 그려진다
        Graph before = graph(Set.of(), GET, FIND);
        Graph after = graph(Set.of(edge(GET, CANCEL)), GET, FIND, CANCEL);

        String md = render(before, after);

        assertThat(md).contains("shop_OrderService_cancel_Long_[\"OrderService.cancel\"]:::added")
                .contains("shop_OrderController_get_Long_[\"GET /orders/{id}<br/>OrderController.get\"]\n")
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
        Node cancelController = new Node("shop.OrderController#cancel(Long)", "shop.OrderController", "cancel",
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
        Node forceCancel = new Node("shop.AdminController#forceCancel(Long)", "shop.AdminController", "forceCancel",
                Layer.CONTROLLER, "DELETE /admin/orders/{id}", "1", "shop/AdminController.java");
        Node cancelController = new Node("shop.OrderController#cancel(Long)", "shop.OrderController", "cancel",
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
        Node cancelController = new Node("shop.OrderController#cancel(Long)", "shop.OrderController", "cancel",
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
    void 기능_subgraph_전용_노드는_레이어_subgraph_에서_빠짐() {
        Node cancelController = new Node("shop.OrderController#cancel(Long)", "shop.OrderController", "cancel",
                Layer.CONTROLLER, "DELETE /orders/{id}", "1", "shop/OrderController.java");
        // 새 엔드포인트가 기존 find 도 부름 — find 는 get 도 부르는 공유 노드라 기능에 안 묶이고 레이어 칸에 남는다
        // get 은 같은 커밋에서 본문이 바뀌어 컨트롤러 칸에 그려진다
        Node getChanged = new Node(GET.id(), GET.fqn(), GET.method(), GET.layer(), GET.endpoint(), "2", GET.file());
        Graph before = graph(Set.of(edge(GET, FIND)), GET, FIND);
        Graph after = graph(Set.of(edge(GET, FIND), edge(cancelController, CANCEL), edge(cancelController, FIND)),
                getChanged, FIND, cancelController, CANCEL);

        String md = render(before, after);

        assertThat(md).contains("subgraph F_shop_OrderController_cancel_Long_[\"DELETE /orders/{id}\"]")
                .contains("shop_OrderService_cancel_Long_[\"OrderService.cancel\"]:::added");
        String serviceBlock = md.substring(md.indexOf("subgraph SERVICE"), md.indexOf("  end", md.indexOf("subgraph SERVICE")));
        String controllerBlock = md.substring(md.indexOf("subgraph CONTROLLER"), md.indexOf("  end", md.indexOf("subgraph CONTROLLER")));
        assertThat(serviceBlock).doesNotContain("cancel").contains("OrderService.find");
        assertThat(controllerBlock).doesNotContain("cancel").contains("OrderController.get");
    }

    @Test
    void 범례는_diff_없으면_아예_없음() {
        Graph same = graph(Set.of(edge(GET, FIND)), GET, FIND);

        assertThat(render(same, same)).doesNotContain("LEGEND").doesNotContain("범례");
    }

    @Test
    void 범례는_실제_등장한_색만_보여줌() {
        Graph before = graph(Set.of(), FIND);
        Graph after = graph(Set.of(), service("find", 1, "2"));

        assertThat(render(before, after)).contains("subgraph LEGEND[\"범례\"]")
                .contains("legend_changed[\"노드 변경\"]:::changed")
                .doesNotContain("legend_added").doesNotContain("legend_removed");

        Graph before2 = graph(Set.of(), GET);
        Graph after2 = graph(Set.of(), GET, FIND);
        assertThat(render(before2, after2)).contains("legend_added[\"노드 추가\"]:::added")
                .doesNotContain("legend_removed").doesNotContain("legend_changed");
    }

    @Test
    void 같은_흐름_안에서_두_갈래로_만나는_노드는_기능에_흡수됨() {
        Node cancelController = new Node("shop.OrderController#cancel(Long)", "shop.OrderController", "cancel",
                Layer.CONTROLLER, "DELETE /orders/{id}", "1", "shop/OrderController.java");
        Node notify = service("notify", 1, "1");
        Node audit = service("audit", 1, "1");
        // 다이아몬드: cancel -> {CANCEL, notify} -> audit (audit 로 들어오는 화살표 2개, 전부 이 기능 안에서 옴)
        Graph before = graph(Set.of());
        Graph after = graph(Set.of(edge(cancelController, CANCEL), edge(cancelController, notify),
                edge(CANCEL, audit), edge(notify, audit)), cancelController, CANCEL, notify, audit);

        String md = render(before, after);

        assertThat(md).contains("| 기능 추가 | DELETE /orders/{id} |")
                .contains("shop_OrderService_audit_Long_[\"OrderService.audit\"]:::added")
                .doesNotContain("| 추가 | OrderService.audit |")
                .doesNotContain("| 호출 추가 | OrderService.cancel → OrderService.audit |")
                .doesNotContain("| 호출 추가 | OrderService.notify → OrderService.audit |");
    }

    @Test
    void 추가_엣지는_초록_삭제_엣지는_빨간_linkStyle() {
        Graph before = graph(Set.of(edge(GET, CANCEL)), GET, FIND, CANCEL);
        Graph after = graph(Set.of(edge(GET, FIND)), GET, FIND, CANCEL);

        String md = render(before, after);

        // 엣지는 id 순 — cancel 이 find 보다 앞이라 삭제된 cancel 호출이 0번
        assertThat(md).contains("    shop_OrderController_get_Long_ --> shop_OrderService_cancel_Long_\n"
                        + "    shop_OrderController_get_Long_ --> shop_OrderService_find_Long_\n")
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
    void 오버로드만_이름에_파라미터_타입() {
        // 인자 수가 같은 오버로드(find(Long) · find(String))도 서로 다른 상자
        Node byName = new Node("shop.OrderService#find(String)", "shop.OrderService", "find",
                Layer.SERVICE, null, "1", "shop/OrderService.java");
        Graph g = graph(Set.of(), FIND, byName, service("find", 2, "1"), CANCEL);

        assertThat(render(g, g)).contains("[\"OrderService.find(Long)\"]").contains("[\"OrderService.find(String)\"]")
                .contains("[\"OrderService.find(Long,Long)\"]").contains("[\"OrderService.cancel\"]");
    }

    @Test
    void 밑줄_든_타입과_쉼표로_나뉜_타입은_다른_Mermaid_id() {
        // 영숫자 밖을 전부 _ 로 바꾸면 둘 다 f_My_Type_ — 한 상자로 겹친다
        Node joined = new Node("shop.OrderService#f(My_Type)", "shop.OrderService", "f",
                Layer.SERVICE, null, "1", "shop/OrderService.java");
        Node split = new Node("shop.OrderService#f(My,Type)", "shop.OrderService", "f",
                Layer.SERVICE, null, "1", "shop/OrderService.java");
        Graph g = graph(Set.of(), joined, split);

        assertThat(render(g, g)).contains("shop_OrderService_f_My__Type_[").contains("shop_OrderService_f_My_Type_[");
    }

    @Test
    void 엔드포인트_속_따옴표_꺾쇠_이스케이프() {
        Node odd = new Node("shop.OrderController#odd()", "shop.OrderController", "odd",
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

        assertThat(md).contains("shop_OrderController_get_Long_ --> shop_OrderService_find_Long_")
                .doesNotContain(":::").contains("첫 스냅샷");
    }

    @Test
    void 변경과_이어지지_않은_노드는_빠지고_생략_개수만() {
        Graph before = graph(Set.of(edge(GET, FIND)), GET, FIND, CANCEL);
        Graph after = graph(Set.of(edge(GET, FIND)), GET, service("find", 1, "2"), CANCEL);

        String md = render(before, after);

        // 바뀐 find 와 그 호출자 get 만 — 아무 데도 안 이어진 cancel 은 그림 밖
        assertThat(md).contains("[\"OrderService.find\"]:::changed")
                .contains("shop_OrderController_get_Long_[")
                .doesNotContain("OrderService.cancel")
                .contains("변경과 무관한 노드 1개 생략")
                .as("외 N곳 상자가 없으면 그 스타일도 없음").doesNotContain("classDef more");
    }

    @Test
    void 새_호출의_기존_도착점은_그리되_그_호출자는_펼치지_않음() {
        Graph before = graph(Set.of(edge(GET, FIND)), GET, FIND);
        Graph after = graph(Set.of(edge(GET, FIND), edge(CANCEL, FIND)), GET, FIND, CANCEL);

        String md = render(before, after);

        // find 는 바뀌지 않았고 새로 불릴 뿐 — 원래 부르던 get 은 이 커밋과 무관
        assertThat(md).contains("shop_OrderService_find_Long_[")
                .doesNotContain("OrderController.get")
                .contains("변경과 무관한 노드 1개 생략");
    }

    @Test
    void 문맥이_상한을_넘으면_외_N곳_상자() {
        Node target = plain("Target", "run", Layer.INTERNAL);
        Node changed = new Node(target.id(), target.fqn(), target.method(), target.layer(), null, "2", target.file());
        Node[] callers = new Node[7];
        Set<Edge> edges = new java.util.HashSet<>();
        for (int i = 0; i < callers.length; i++) {
            callers[i] = plain("C" + i, "run", Layer.ENTRY);
            edges.add(edge(callers[i], target));
        }
        Graph before = graph(edges, Stream.concat(Stream.of(callers), Stream.of(target)).toArray(Node[]::new));
        Graph after = graph(edges, Stream.concat(Stream.of(callers), Stream.of(changed)).toArray(Node[]::new));

        String md = render(before, after);

        // id 순 앞 5곳만 그리고 나머지 둘은 상자 하나 — 상자로 가는 화살표도 같은 흐름도 안에
        assertThat(md).contains("app_C0_run__[").contains("app_C4_run__[")
                .doesNotContain("app_C5_run__").doesNotContain("app_C6_run__")
                .contains("    more_in_app_Target_run__[\"호출자 외 2곳\"]:::more\n")
                .contains("    more_in_app_Target_run__ --> app_Target_run__\n")
                .contains("변경과 무관한 노드 2개 생략");
    }

    static Node plain(String cls, String method, Layer layer) {
        return new Node("app." + cls + "#" + method + "()", "app." + cls, method, layer, null, "1", "app/" + cls + ".java");
    }

    @Test
    void 진입_내부_칸은_한글_제목으로_진입이_먼저() {
        Node main = plain("App", "main", Layer.ENTRY);
        Node run = plain("Pipeline", "run", Layer.INTERNAL);

        String md = render(null, graph(Set.of(edge(main, run)), main, run));

        assertThat(md).contains("  subgraph ENTRY[\"진입\"]\n    app_App_main__[\"App.main\"]\n")
                .contains("  subgraph INTERNAL[\"내부\"]\n    app_Pipeline_run__[\"Pipeline.run\"]\n");
        assertThat(md.indexOf("subgraph ENTRY")).isLessThan(md.indexOf("subgraph INTERNAL"));
    }

    @Test
    void 새_진입점은_클래스_메서드_라벨로_기능_추가_같은_이름_핸들러도_구분() {
        Node gift = plain("GiftListener", "onSlash", Layer.ENTRY);
        Node ego = plain("EgoListener", "onSlash", Layer.ENTRY);
        Node save = plain("Store", "save", Layer.INTERNAL);
        Node load = plain("Catalog", "load", Layer.INTERNAL);
        Graph before = graph(Set.of(edge(gift, load)), gift, load);
        Graph after = graph(Set.of(edge(gift, load), edge(ego, save)), gift, load, ego, save);

        String md = render(before, after);

        assertThat(md).contains("  subgraph F_app_EgoListener_onSlash__[\"EgoListener.onSlash\"]\n")
                .contains("| 기능 추가 | EgoListener.onSlash |")
                .doesNotContain("| 추가 | Store.save |");
    }
}
