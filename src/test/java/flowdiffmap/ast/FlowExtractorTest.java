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
import java.util.ArrayList;
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
    void 파싱_실패_파일만_건너뜀() throws IOException {
        List<Path> files = new ArrayList<>(javaFiles(V1));
        files.add(Path.of("src/test/resources/fixture/broken/Broken.java"));

        Graph g = new FlowExtractor(V1).extract(files);

        assertThat(g.nodes()).hasSize(6);
        assertThat(g.edges()).hasSize(5);
    }

    @Test
    void 주석만_바뀐_본문은_같은_해시(@TempDir Path dir) throws IOException {
        String before = """
                package shop;
                @org.springframework.stereotype.Service
                public class S {
                    public int f() { return 1; }
                }
                """;
        String commented = """
                package shop;
                @org.springframework.stereotype.Service
                public class S {
                    /** 설명 */
                    public int f() {
                        // 주석
                        return 1; /* 끝 */
                    }
                }
                """;
        String changed = before.replace("return 1;", "return 2;");

        assertThat(hashOf(dir, commented)).isEqualTo(hashOf(dir, before));
        assertThat(hashOf(dir, changed)).isNotEqualTo(hashOf(dir, before));
    }

    static String hashOf(Path dir, String source) throws IOException {
        Path file = Files.createDirectories(dir.resolve("shop")).resolve("S.java");
        Files.writeString(file, source);
        return new FlowExtractor(dir).extract(List.of(file)).nodes().get("shop.S#f/0").bodyHash();
    }
}
