package flowdiffmap.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import flowdiffmap.graph.Component;
import flowdiffmap.graph.Edge;
import flowdiffmap.graph.Graph;
import flowdiffmap.graph.Layer;
import flowdiffmap.graph.Node;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class GraphStoreTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17");

    static final Component C = new Component("shop.C", Layer.CONTROLLER, "shop/C.java");
    static final Component S = new Component("shop.S", Layer.SERVICE, "shop/S.java");
    // 선언 메서드 없이 상속만 하는 Spring Data 리포지토리
    static final Component R = new Component("shop.R", Layer.REPOSITORY, "shop/R.java");

    GraphStore store;

    static Node node(Component c, String method, String hash) {
        return new Node(c.fqn() + "#" + method + "/0", c.fqn(), method, c.layer(), null, hash, c.file());
    }

    static Edge edge(Node from, String to) {
        return new Edge(from.id(), to, from.file());
    }

    static Graph graph(Set<Component> components, Set<Edge> edges, Node... nodes) {
        return new Graph(Stream.of(nodes).collect(Collectors.toMap(Node::id, Function.identity())), edges, components);
    }

    static final Node GET = node(C, "get", "1");
    static final Node FIND = node(S, "find", "1");
    static final Node OLD = node(S, "old", "1");

    /** 부모 {@code p} — C#get → S#find → R#findById(상속 메서드라 노드 없음). */
    @BeforeEach
    void parent() throws SQLException {
        try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            c.createStatement().execute("DROP TABLE IF EXISTS snapshot, component, node, edge");
        }
        store = new GraphStore(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        store.saveFull("p", graph(Set.of(C, S, R),
                Set.of(edge(GET, FIND.id()), edge(FIND, "shop.R#findById/1")),
                GET, FIND, OLD));
    }

    @Test
    void 부모_복사_후_바뀐_파일만_교체() throws SQLException {
        Node add = node(S, "add", "1");
        store.saveIncremental("p", "c", Set.of(S.file()),
                graph(Set.of(S), Set.of(edge(FIND, "shop.R#findById/1")), FIND, add));

        Graph g = store.load("c").orElseThrow();

        // 상속 메서드 callee 는 암묵 노드로 채워진다
        assertThat(g.nodes().values())
                .extracting(Node::id, Node::layer)
                .containsExactlyInAnyOrder(
                        tuple(GET.id(), Layer.CONTROLLER),
                        tuple(FIND.id(), Layer.SERVICE),
                        tuple(add.id(), Layer.SERVICE),
                        tuple("shop.R#findById/1", Layer.REPOSITORY));
        assertThat(g.edges()).containsExactlyInAnyOrder(edge(GET, FIND.id()), edge(FIND, "shop.R#findById/1"));
        // 부모 스냅샷은 그대로
        assertThat(store.load("p").orElseThrow().nodes()).containsKey(OLD.id());
    }

    @Test
    void 삭제된_파일의_행_제거() throws SQLException {
        store.saveIncremental("p", "c", Set.of(C.file()), graph(Set.of(), Set.of()));

        Graph g = store.load("c").orElseThrow();

        assertThat(g.nodes()).doesNotContainKey(GET.id());
        assertThat(g.edges()).containsExactly(edge(FIND, "shop.R#findById/1"));
        assertThat(g.components()).containsExactlyInAnyOrder(S, R);
    }

    @Test
    void 컴포넌트가_아니게_된_callee_로_가는_엣지_정리() throws SQLException {
        // R.java 만 바뀌고 어노테이션 · 상속이 빠짐 — 호출하는 S.java 는 그대로
        store.saveIncremental("p", "c", Set.of(R.file()), graph(Set.of(), Set.of()));

        Graph g = store.load("c").orElseThrow();

        assertThat(g.edges()).containsExactly(edge(GET, FIND.id()));
        assertThat(g.nodes()).doesNotContainKey("shop.R#findById/1");
    }

    @Test
    void 컴포넌트가_된_callee_로_가는_엣지는_안_바뀐_호출자에서도_살아남() throws SQLException {
        // 부모에서 V 는 컴포넌트가 아니라 C#get → V#check 가 저장만 되고 읽히지 않는다
        Component v = new Component("shop.V", Layer.SERVICE, "shop/V.java");
        Node check = node(v, "check", "1");
        store.saveFull("p0", graph(Set.of(C), Set.of(edge(GET, check.id())), GET));
        assertThat(store.load("p0").orElseThrow().edges()).isEmpty();

        // V.java 에만 @Service 가 붙음 — C.java 는 그대로
        store.saveIncremental("p0", "c", Set.of(v.file()), graph(Set.of(v), Set.of(), check));

        assertThat(store.load("c").orElseThrow().edges()).containsExactly(edge(GET, check.id()));
    }

    @Test
    void 부모_스냅샷이_없으면_저장하지_않음() throws SQLException {
        assertThatThrownBy(() -> store.saveIncremental("missing", "c", Set.of(S.file()), graph(Set.of(S), Set.of(), FIND)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.load("c")).isEmpty();
    }

    @Test
    void 같은_커밋을_다시_저장하면_덮어씀() throws SQLException {
        store.saveIncremental("p", "c", Set.of(S.file()), graph(Set.of(S), Set.of(), FIND));
        store.saveIncremental("p", "c", Set.of(S.file()), graph(Set.of(S), Set.of(), FIND));

        assertThat(store.load("c").orElseThrow().nodes()).containsOnlyKeys(GET.id(), FIND.id());
    }

    @Test
    void 스프링_리포에서는_진입_내부_클래스를_버림() throws SQLException {
        Component app = new Component("shop.App", Layer.ENTRY, "shop/App.java");
        Component order = new Component("shop.Order", Layer.INTERNAL, "shop/Order.java");
        Node main = node(app, "main", "1");
        Node amount = node(order, "getAmount", "1");
        store.saveFull("s", graph(Set.of(C, S, app, order),
                Set.of(edge(GET, FIND.id()), edge(main, FIND.id()), edge(FIND, amount.id())),
                GET, FIND, main, amount));

        Graph g = store.load("s").orElseThrow();

        assertThat(g.nodes()).containsOnlyKeys(GET.id(), FIND.id());
        assertThat(g.edges()).containsExactly(edge(GET, FIND.id()));
        assertThat(g.components()).containsExactlyInAnyOrder(C, S);
    }

    @Test
    void 스프링_아닌_리포는_진입점에서_닿는_것만() throws SQLException {
        Component app = new Component("app.App", Layer.ENTRY, "app/App.java");
        Component pipe = new Component("app.Pipeline", Layer.INTERNAL, "app/Pipeline.java");
        Component back = new Component("app.Back", Layer.INTERNAL, "app/Back.java");
        Component lib = new Component("app.Lib", Layer.INTERNAL, "app/Lib.java");
        Component unused = new Component("app.Unused", Layer.INTERNAL, "app/Unused.java");
        Node main = node(app, "main", "1");
        Node run = node(pipe, "run", "1");
        Node call = node(back, "call", "1");
        Node idle = node(unused, "idle", "1");
        // Pipeline ↔ Back 순환 · Lib#inherited 는 노드 행 없는 암묵 노드 · Unused 는 아무도 안 부름
        store.saveFull("n", graph(Set.of(app, pipe, back, lib, unused),
                Set.of(edge(main, run.id()), edge(run, call.id()), edge(call, run.id()),
                        edge(run, "app.Lib#inherited/0"), edge(idle, run.id())),
                main, run, call, idle));

        Graph g = store.load("n").orElseThrow();

        assertThat(g.nodes()).containsOnlyKeys(main.id(), run.id(), call.id(), "app.Lib#inherited/0");
        assertThat(g.nodes().get("app.Lib#inherited/0").layer()).isEqualTo(Layer.INTERNAL);
        assertThat(g.edges()).containsExactlyInAnyOrder(edge(main, run.id()), edge(run, call.id()),
                edge(call, run.id()), edge(run, "app.Lib#inherited/0"));
        assertThat(g.components()).containsExactlyInAnyOrder(app, pipe, back, lib);
    }

    @Test
    void 진입점_없는_스프링_아닌_리포는_빈_그래프() throws SQLException {
        Component lib = new Component("app.Lib", Layer.INTERNAL, "app/Lib.java");
        store.saveFull("l", graph(Set.of(lib), Set.of(), node(lib, "call", "1")));

        Graph g = store.load("l").orElseThrow();

        assertThat(g.nodes()).isEmpty();
        assertThat(g.edges()).isEmpty();
    }

    @Test
    void 없는_커밋은_empty() throws SQLException {
        assertThat(store.load("nope")).isEmpty();
    }
}
