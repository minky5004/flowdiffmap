package flowdiffmap.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import flowdiffmap.graph.Component;
import flowdiffmap.graph.Edge;
import flowdiffmap.graph.Graph;
import flowdiffmap.graph.Layer;
import flowdiffmap.graph.Node;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowExtractorTest {

    static final Path V1 = Path.of("src/test/resources/fixture/v1");
    static final String PKG = "shop.order.";

    static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    Graph extractV1() throws IOException {
        return new FlowExtractor(V1).extract(javaFiles(V1));
    }

    @Test
    void 레이어와_엔드포인트() throws IOException {
        Graph g = extractV1();

        assertThat(g.nodes().values())
                .extracting(Node::id, Node::layer, Node::endpoint, Node::file)
                .containsExactlyInAnyOrder(
                        tuple(PKG + "OrderController#get(Long)", Layer.CONTROLLER, "GET /orders/{id}", "shop/order/OrderController.java"),
                        tuple(PKG + "OrderController#create(Order)", Layer.CONTROLLER, "POST /orders", "shop/order/OrderController.java"),
                        tuple(PKG + "OrderService#find(Long)", Layer.SERVICE, null, "shop/order/OrderService.java"),
                        tuple(PKG + "OrderService#create(Order)", Layer.SERVICE, null, "shop/order/OrderService.java"),
                        tuple(PKG + "OrderRepository#findByStatus(String)", Layer.REPOSITORY, null, "shop/order/OrderRepository.java"),
                        tuple(PKG + "PaymentRepository#charge(Order)", Layer.REPOSITORY, null, "shop/order/PaymentRepository.java"),
                        tuple(PKG + "Order#getAmount()", Layer.INTERNAL, null, "shop/order/Order.java"));
        // 엔티티 Order 는 INTERNAL 로 저장 — Spring 리포에서는 GraphStore.load 가 거른다
        assertThat(g.components())
                .extracting(Component::fqn, Component::layer)
                .containsExactlyInAnyOrder(
                        tuple(PKG + "OrderController", Layer.CONTROLLER),
                        tuple(PKG + "OrderService", Layer.SERVICE),
                        tuple(PKG + "OrderRepository", Layer.REPOSITORY),
                        tuple(PKG + "PaymentRepository", Layer.REPOSITORY),
                        tuple(PKG + "Order", Layer.INTERNAL));
    }

    static final Path PLAIN = Path.of("src/test/resources/fixture/plain");

    @Test
    void 스프링_아닌_클래스는_진입_또는_내부() throws IOException {
        Graph g = new FlowExtractor(PLAIN).extract(javaFiles(PLAIN));

        // main · 프레임워크 타입의 @Override 만 진입점 노드 — 진입 클래스의 그 밖 private 아닌 메서드(Bot#status · save)와
        // Object 메서드 재정의(Bot#toString)는 내부 노드 — 다른 클래스가 부르는 리스너 유틸이 진입 칸 · 빈 해시 암묵 노드로 새지 않게
        // 소스 안 상위 타입은 거슬러 올라간다(PingCommand → BaseCommand → TimerTask) · Java 25 인스턴스 main(Script)도 진입점
        // 진입점 아님: 소스 안 인터페이스 구현(PortImpl) · JDK 비콜백 타입 구현(Money) · String[] 아닌 main(Tool) ·
        // record(Named) · 인터페이스(Port) · 로컬 클래스와 그 안 중첩 클래스(Local · Inner)
        assertThat(g.nodes().values())
                .extracting(Node::id, Node::layer, Node::endpoint)
                .containsExactlyInAnyOrder(
                        tuple("app.App#main(String[])", Layer.ENTRY, null),
                        tuple("app.Bot#run()", Layer.ENTRY, null),
                        tuple("app.Bot#status()", Layer.INTERNAL, null),
                        tuple("app.Bot#save()", Layer.INTERNAL, null),
                        tuple("app.Bot#toString()", Layer.INTERNAL, null),
                        tuple("app.PingCommand#run()", Layer.ENTRY, null),
                        tuple("app.Script#main()", Layer.ENTRY, null),
                        tuple("app.Money#compareTo(Money)", Layer.INTERNAL, null),
                        tuple("app.Money#toString()", Layer.INTERNAL, null),
                        tuple("app.Tool#main(int)", Layer.INTERNAL, null),
                        tuple("app.Pipeline#run()", Layer.INTERNAL, null),
                        tuple("app.Cleaner#clean()", Layer.INTERNAL, null),
                        tuple("app.Store#save()", Layer.INTERNAL, null),
                        tuple("app.Unused#idle()", Layer.INTERNAL, null),
                        tuple("app.PortImpl#send()", Layer.INTERNAL, null));
        assertThat(g.components())
                .extracting(Component::fqn, Component::layer)
                .containsExactlyInAnyOrder(
                        tuple("app.App", Layer.ENTRY),
                        tuple("app.Bot", Layer.ENTRY),
                        tuple("app.PingCommand", Layer.ENTRY),
                        tuple("app.Script", Layer.ENTRY),
                        tuple("app.BaseCommand", Layer.INTERNAL),
                        tuple("app.Money", Layer.INTERNAL),
                        tuple("app.Tool", Layer.INTERNAL),
                        tuple("app.Pipeline", Layer.INTERNAL),
                        tuple("app.Cleaner", Layer.INTERNAL),
                        tuple("app.Store", Layer.INTERNAL),
                        tuple("app.Unused", Layer.INTERNAL),
                        tuple("app.PortImpl", Layer.INTERNAL));
    }

    @Test
    void 스프링_아닌_클래스의_static_호출과_헬퍼_경유_호출도_엣지() throws IOException {
        Graph g = new FlowExtractor(PLAIN).extract(javaFiles(PLAIN));

        assertThat(g.edges())
                .extracting(Edge::from, Edge::to)
                .containsExactlyInAnyOrder(
                        tuple("app.App#main(String[])", "app.Pipeline#run()"),
                        tuple("app.Pipeline#run()", "app.Cleaner#clean()"),
                        // run → save(같은 클래스 · 엣지 아님) → Store#save — save 가 노드여도 run 의 헬퍼로 흡수돼야
                        // 진입점에서 닿는 흐름이 끊기지 않는다
                        tuple("app.Bot#run()", "app.Store#save()"),
                        tuple("app.Bot#save()", "app.Store#save()"),
                        tuple("app.Script#main()", "app.Store#save()"));
    }

    @Test
    void 소스_안_다른_클래스로의_호출만_엣지로() throws IOException {
        Graph g = extractV1();

        // 상속 메서드(save · findById)는 JpaRepository 가 타입 솔버에 없어도 scope 타입으로 잡힌다
        // 엔티티 호출(order.getAmount())도 엣지 — 컴포넌트 여부는 스냅샷을 읽을 때 거른다
        // Optional 체인(orElseThrow)은 소스 밖 타입이라 엣지가 아니다
        assertThat(g.edges())
                .extracting(Edge::from, Edge::to)
                .containsExactlyInAnyOrder(
                        tuple(PKG + "OrderController#get(Long)", PKG + "OrderService#find(Long)"),
                        tuple(PKG + "OrderController#create(Order)", PKG + "OrderService#create(Order)"),
                        tuple(PKG + "OrderService#find(Long)", PKG + "OrderRepository#findById(?)"),
                        tuple(PKG + "OrderService#create(Order)", PKG + "PaymentRepository#charge(Order)"),
                        tuple(PKG + "OrderService#create(Order)", PKG + "OrderRepository#save(?)"),
                        tuple(PKG + "OrderService#create(Order)", PKG + "Order#getAmount()"));
        assertThat(g.edges())
                .filteredOn(e -> e.from().startsWith(PKG + "OrderService"))
                .extracting(Edge::file)
                .containsOnly("shop/order/OrderService.java");
    }

    @Test
    void 파싱_실패_파일과_소스_루트_밖_파일은_건너뜀(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("src"));
        Path good = write(root, "shop/S.java", service("public int f() { return 1; }"));
        Path broken = write(root, "shop/Broken.java", "package shop; @Service public class Broken { void oops( { } }");
        Path outside = write(dir, "Outside.java", service("public int g() { return 1; }").replace("class S", "class Outside"));

        Graph g = new FlowExtractor(root).extract(List.of(broken, outside, good));

        assertThat(g.nodes()).containsOnlyKeys("shop.S#f()");
    }

    @Test
    void 헬퍼_메서드를_거친_호출도_엔트리의_엣지() throws IOException {
        // OrderService#create 는 private pay() 를 거쳐 PaymentRepository#charge 를 부른다
        Graph g = extractV1();

        assertThat(g.nodes()).doesNotContainKey(PKG + "OrderService#pay(Order)");
        assertThat(g.edges()).extracting(Edge::from, Edge::to)
                .contains(tuple(PKG + "OrderService#create(Order)", PKG + "PaymentRepository#charge(Order)"));
    }

    @Test
    void 해시는_주석을_뺀_선언_전체와_헬퍼(@TempDir Path dir) throws IOException {
        String base = service("""
                @Transactional
                public int f(long x) { return helper(); }
                private int helper() { return 1; }
                """);
        String commented = service("""
                /** 설명 */
                @Transactional
                public int f(long x) {
                    // 주석
                    return helper(); /* 끝 */
                }
                private int helper() { return 1; }
                """);

        String hash = hashOf(dir, base, "shop.S#f(long)");
        assertThat(hashOf(dir, commented, "shop.S#f(long)")).isEqualTo(hash);
        assertThat(hashOf(dir, base.replace("return 1;", "return 2;"), "shop.S#f(long)")).as("헬퍼 본문").isNotEqualTo(hash);
        assertThat(hashOf(dir, base.replace("@Transactional", ""), "shop.S#f(long)")).as("어노테이션").isNotEqualTo(hash);
        // 파라미터 타입은 id 의 일부 — 바꾸면 해시가 아니라 노드가 바뀐다(다른 시그니처 · 삭제 + 추가로 칠해짐)
        assertThat(hashOf(dir, base.replace("long x", "int x"), "shop.S#f(int)")).as("파라미터 타입").isNotNull();

        String query = """
                package shop;
                public interface R extends org.springframework.data.jpa.repository.JpaRepository<Object, Long> {
                    @Query("select a from A a")
                    java.util.List<Object> q();
                }
                """;
        assertThat(hashOf(dir, query.replace("from A", "from B"), "shop.R#q()"))
                .as("본문 없는 쿼리 메서드").isNotEqualTo(hashOf(dir, query, "shop.R#q()"));
    }

    @Test
    void 인자_수가_같은_오버로드는_다른_노드와_엣지(@TempDir Path dir) throws IOException {
        // 인자 수만으로 id 를 지으면 find(Long) · find(String) 이 한 노드로 합쳐져 하나가 사라진다
        Path service = write(dir, "shop/S.java", service("""
                public int find(Long id) { return 1; }
                public int find(String name) { return 2; }
                """));
        Path controller = write(dir, "shop/C.java", """
                package shop;
                @RestController
                public class C {
                    private final S s = new S();
                    @GetMapping("/a")
                    public int a() { return s.find(Long.valueOf(1)); }
                    @GetMapping("/b")
                    public int b() { return s.find("x"); }
                }
                """);

        Graph g = new FlowExtractor(dir).extract(List.of(service, controller));

        assertThat(g.nodes()).containsKeys("shop.S#find(Long)", "shop.S#find(String)");
        assertThat(g.edges()).extracting(Edge::from, Edge::to).containsOnly(
                tuple("shop.C#a()", "shop.S#find(Long)"),
                tuple("shop.C#b()", "shop.S#find(String)"));
    }

    @Test
    void 대상을_못_고른_오버로드_호출은_후보_전부로(@TempDir Path dir) throws IOException {
        // 인자 타입이 소스 밖(jar)이면 솔버가 오버로드를 못 고른다 — 호출을 잃는 것보다 가능한 흐름을 다 잇는 쪽
        Path service = write(dir, "shop/S.java", service("""
                public int find(com.ext.A a) { return 1; }
                public int find(com.ext.B b) { return 2; }
                """));
        Path controller = write(dir, "shop/C.java", """
                package shop;
                @RestController
                public class C {
                    private final S s = new S();
                    @GetMapping("/a")
                    public int a(com.ext.A x) { return s.find(x); }
                }
                """);

        Graph g = new FlowExtractor(dir).extract(List.of(service, controller));

        assertThat(g.edges()).extracting(Edge::from, Edge::to).containsOnly(
                tuple("shop.C#a(com.ext.A)", "shop.S#find(com.ext.A)"),
                tuple("shop.C#a(com.ext.A)", "shop.S#find(com.ext.B)"));
    }

    @Test
    void 이름이_같은_두_타입의_오버로드도_다른_노드(@TempDir Path dir) throws IOException {
        // 단순 이름으로 줄이면 둘 다 f(Id) — 소스가 패키지째 적은 표기를 그대로 둔다
        Path file = write(dir, "shop/S.java", service("""
                public void f(com.a.Id id) { }
                public void f(com.b.Id id) { }
                """));

        Graph g = new FlowExtractor(dir).extract(List.of(file));

        assertThat(g.nodes()).containsOnlyKeys("shop.S#f(com.a.Id)", "shop.S#f(com.b.Id)");
    }

    @Test
    void 가변_인자_호출은_선언된_노드로(@TempDir Path dir) throws IOException {
        Path service = write(dir, "shop/S.java", service("public void log(String... parts) { }"));
        Path controller = write(dir, "shop/C.java", """
                package shop;
                @RestController
                public class C {
                    private final S s = new S();
                    @GetMapping("/a")
                    public void a() { s.log("x", "y"); }
                }
                """);

        Graph g = new FlowExtractor(dir).extract(List.of(service, controller));

        assertThat(g.edges()).extracting(Edge::from, Edge::to).containsOnly(tuple("shop.C#a()", "shop.S#log(String...)"));
    }

    @Test
    void 메서드_배열_RequestMapping(@TempDir Path dir) throws IOException {
        Path file = write(dir, "shop/C.java", """
                package shop;
                @RestController
                @RequestMapping(path = "/c")
                public class C {
                    @RequestMapping(value = "/s", method = {RequestMethod.GET, RequestMethod.POST})
                    public void s() { }
                }
                """);

        Graph g = new FlowExtractor(dir).extract(List.of(file));

        assertThat(g.nodes().get("shop.C#s()").endpoint()).isEqualTo("GET,POST /c/s");
    }

    static String service(String members) {
        return "package shop;\n@org.springframework.stereotype.Service\npublic class S {\n" + members + "\n}\n";
    }

    static Path write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, source);
    }

    static String hashOf(Path dir, String source, String id) throws IOException {
        String rel = "shop/" + id.substring("shop.".length(), id.indexOf('#')) + ".java";
        Path file = write(dir, rel, source);
        return new FlowExtractor(dir).extract(List.of(file)).nodes().get(id).bodyHash();
    }
}
