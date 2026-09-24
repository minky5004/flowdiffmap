package flowdiffmap;

import static org.assertj.core.api.Assertions.assertThat;

import flowdiffmap.store.GraphStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 임시 git 리포에 fixture 를 커밋해 가며 훅이 부를 {@link Main#run} 을 커밋마다 돌린다. */
@Testcontainers
class PipelineTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17");

    static final Path FIXTURE = Path.of("src/test/resources/fixture");

    @TempDir
    Path repo;
    GraphStore store;
    Path out;

    @BeforeEach
    void init() throws Exception {
        // 같은 내용 · 같은 초의 커밋은 테스트가 달라도 SHA 가 같다 — 앞 테스트의 스냅샷이 부모로 잡히지 않게
        try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            c.createStatement().execute("DROP TABLE IF EXISTS snapshot, component, node, edge");
        }
        store =new GraphStore(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        out = repo.resolve("docs/flow/request-flow.md");
        git("init", "-q");
    }

    @Test
    void 첫_커밋은_하이라이트_없는_베이스라인() throws Exception {
        commit("v1");

        Main.run(repo, store);

        assertThat(Files.readString(out))
                .contains("POST /orders<br/>OrderController.create")
                .contains("첫 스냅샷")
                .doesNotContain(":::");
    }

    @Test
    void 다음_커밋은_부모_대비_추가_삭제_변경을_칠함() throws Exception {
        commit("v1");
        Main.run(repo, store);
        commit("v2");

        Main.run(repo, store);

        assertThat(Files.readString(out))
                .contains("[\"DELETE /orders/{id}<br/>OrderController.cancel\"]:::added")
                .contains("[\"OrderService.cancel\"]:::added")
                .contains("[\"OrderService.create\"]:::removed")
                .contains("[\"OrderService.find\"]:::changed")
                .contains("[\"GET /orders/{id}<br/>OrderController.get\"]\n");
    }

    @Test
    void java_변경_없는_커밋은_출력을_건드리지_않고_스냅샷만_이어감() throws Exception {
        commit("v1");
        Main.run(repo, store);
        commit("v2");
        Main.run(repo, store);
        String before = Files.readString(out);
        git("add", "docs");
        git("commit", "-q", "-m", "docs");

        Main.run(repo, store);

        assertThat(Files.readString(out)).isEqualTo(before);
        assertThat(store.load(git("rev-parse", "HEAD").strip())).isPresent();
    }

    @Test
    void 부모_스냅샷이_없으면_전체_스캔_베이스라인() throws Exception {
        commit("v1");
        commit("v2");

        Main.run(repo, store);

        assertThat(Files.readString(out))
                .contains("DELETE /orders/{id}<br/>OrderController.cancel")
                .doesNotContain("OrderService.create")
                .contains("첫 스냅샷");
    }

    /** {@code src/main/java} 를 fixture 버전으로 통째로 갈아 끼우고 커밋. */
    void commit(String version) throws Exception {
        Path src = repo.resolve("src/main/java");
        if (Files.exists(src)) {
            try (Stream<Path> old = Files.walk(src)) {
                for (Path p : old.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Path from = FIXTURE.resolve(version);
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path to = src.resolve(from.relativize(f).toString());
                Files.createDirectories(to.getParent());
                Files.copy(f, to);
            }
        }
        git("add", "-A");
        git("commit", "-q", "-m", version);
    }

    String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t",
                "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(output).isZero();
        return output;
    }
}
