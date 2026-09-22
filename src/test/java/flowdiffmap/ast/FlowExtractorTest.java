package flowdiffmap.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
                        tuple(PKG + "OrderController#get/1", Layer.CONTROLLER, "GET /orders/{id}", "shop/order/OrderController.java"),
                        tuple(PKG + "OrderController#create/1", Layer.CONTROLLER, "POST /orders", "shop/order/OrderController.java"),
                        tuple(PKG + "OrderService#find/1", Layer.SERVICE, null, "shop/order/OrderService.java"),
                        tuple(PKG + "OrderService#create/1", Layer.SERVICE, null, "shop/order/OrderService.java"),
                        tuple(PKG + "OrderRepository#findByStatus/1", Layer.REPOSITORY, null, "shop/order/OrderRepository.java"),
                        tuple(PKG + "PaymentRepository#charge/1", Layer.REPOSITORY, null, "shop/order/PaymentRepository.java"));
    }

    @Test
    void 레이어_간_호출만_엣지로() throws IOException {
        Graph g = extractV1();

        // 상속 메서드(save · findById)는 JpaRepository 가 타입 솔버에 없어도 scope 타입으로 잡힌다
        // 엔티티 호출(order.getAmount())과 Optional 체인(orElseThrow)은 엣지가 아니다
        assertThat(g.edges())
                .extracting(Edge::from, Edge::to)
                .containsExactlyInAnyOrder(
                        tuple(PKG + "OrderController#get/1", PKG + "OrderService#find/1"),
                        tuple(PKG + "OrderController#create/1", PKG + "OrderService#create/1"),
                        tuple(PKG + "OrderService#find/1", PKG + "OrderRepository#findById/1"),
                        tuple(PKG + "OrderService#create/1", PKG + "PaymentRepository#charge/1"),
                        tuple(PKG + "OrderService#create/1", PKG + "OrderRepository#save/1"));
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

        assertThat(g.nodes()).containsOnlyKeys("shop.S#f/0");
    }

    @Test
    void 헬퍼_메서드를_거친_호출도_엔트리의_엣지() throws IOException {
        // OrderService#create 는 private pay() 를 거쳐 PaymentRepository#charge 를 부른다
        Graph g = extractV1();

        assertThat(g.nodes()).doesNotContainKey(PKG + "OrderService#pay/1");
        assertThat(g.edges()).extracting(Edge::from, Edge::to)
                .contains(tuple(PKG + "OrderService#create/1", PKG + "PaymentRepository#charge/1"));
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

        String hash = hashOf(dir, base, "shop.S#f/1");
        assertThat(hashOf(dir, commented, "shop.S#f/1")).isEqualTo(hash);
        assertThat(hashOf(dir, base.replace("return 1;", "return 2;"), "shop.S#f/1")).as("헬퍼 본문").isNotEqualTo(hash);
        assertThat(hashOf(dir, base.replace("long x", "int x"), "shop.S#f/1")).as("파라미터 타입").isNotEqualTo(hash);
        assertThat(hashOf(dir, base.replace("@Transactional", ""), "shop.S#f/1")).as("어노테이션").isNotEqualTo(hash);

        String query = """
                package shop;
                public interface R extends org.springframework.data.jpa.repository.JpaRepository<Object, Long> {
                    @Query("select a from A a")
                    java.util.List<Object> q();
                }
                """;
        assertThat(hashOf(dir, query.replace("from A", "from B"), "shop.R#q/0"))
                .as("본문 없는 쿼리 메서드").isNotEqualTo(hashOf(dir, query, "shop.R#q/0"));
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

        assertThat(g.nodes().get("shop.C#s/0").endpoint()).isEqualTo("GET,POST /c/s");
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
