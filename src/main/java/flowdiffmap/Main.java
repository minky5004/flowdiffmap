package flowdiffmap;

import flowdiffmap.ast.FlowExtractor;
import flowdiffmap.graph.Graph;
import flowdiffmap.render.MermaidRenderer;
import flowdiffmap.store.GraphStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * post-commit 훅이 부르는 진입점 — HEAD 의 스냅샷을 저장하고 부모 대비 흐름도를 {@code docs/flow/request-flow.md} 에 쓴다.
 * 결과 파일은 커밋하지 않는다(훅이 커밋하면 다시 훅이 돈다).
 */
public final class Main {

    static final String SRC = "src/main/java/";
    static final String OUT = "docs/flow/request-flow.md";
    private static final Graph EMPTY = new Graph(Map.of(), Set.of(), Set.of());

    private Main() {
    }

    /** 무슨 일이 있어도 커밋을 막지 않는다 — 실패는 경고 한 줄 · 종료 코드 0. */
    public static void main(String[] args) {
        try {
            Map<String, String> env = System.getenv();
            GraphStore store = new GraphStore(
                    env.getOrDefault("FLOWDIFFMAP_DB_URL", "jdbc:postgresql://localhost:5432/flowdiffmap"),
                    env.getOrDefault("FLOWDIFFMAP_DB_USER", "flowdiffmap"),
                    env.getOrDefault("FLOWDIFFMAP_DB_PASSWORD", "flowdiffmap"));
            run(Path.of(git(Path.of("").toAbsolutePath(), "rev-parse", "--show-toplevel").strip()), store);
        } catch (Exception e) {
            System.err.println("[flowdiffmap] 건너뜀 — " + e);
        }
        System.exit(0);
    }

    static void run(Path repo, GraphStore store) throws IOException, InterruptedException, SQLException {
        Path srcRoot = repo.resolve(SRC);
        // rev-list --parents: "HEAD 부모1 부모2…" · 루트 커밋이면 HEAD 하나 · 머지는 첫 부모 기준
        String[] shas = git(repo, "rev-list", "--parents", "-n", "1", "HEAD").strip().split(" ");
        String sha = shas[0];
        String parent = shas.length > 1 ? shas[1] : null;

        Graph before = parent == null ? null : store.load(parent).orElse(null);
        if (before == null) {
            // 첫 실행 · 루트 커밋 · 훅을 중간에 설치 — 비교할 부모가 없으니 전체를 베이스라인으로
            Graph all = Files.isDirectory(srcRoot) ? new FlowExtractor(srcRoot).extract(javaFiles(srcRoot)) : EMPTY;
            store.saveFull(sha, all);
            write(repo, MermaidRenderer.render(null, store.load(sha).orElseThrow(), sha.substring(0, 7)));
            return;
        }

        Set<String> touched = new HashSet<>();
        List<Path> changed = new ArrayList<>();
        // -z: 한글 등 비 ASCII 경로도 따옴표 · 8진 이스케이프 없이 "상태\0경로\0" 로
        String[] diff = git(repo, "diff", "--name-status", "--no-renames", "-z", parent, sha).split("\0");
        for (int i = 0; i + 1 < diff.length; i += 2) {
            String path = diff[i + 1];
            if (!path.startsWith(SRC) || !path.endsWith(".java")) {
                continue;
            }
            touched.add(path.substring(SRC.length()));
            if (!diff[i].equals("D")) {
                changed.add(repo.resolve(path));
            }
        }
        Graph fresh = changed.isEmpty() ? EMPTY : new FlowExtractor(srcRoot).extract(changed);
        store.saveIncremental(parent, sha, touched, fresh);
        if (touched.isEmpty()) {
            // docs/flow 만 담은 커밋 등 — 다시 쓰면 직전 커밋의 하이라이트가 빈 diff 로 덮인다
            return;
        }
        write(repo, MermaidRenderer.render(before, store.load(sha).orElseThrow(), sha.substring(0, 7)));
    }

    private static List<Path> javaFiles(Path srcRoot) throws IOException {
        try (Stream<Path> files = Files.walk(srcRoot)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    private static void write(Path repo, String markdown) throws IOException {
        Path out = repo.resolve(OUT);
        Files.createDirectories(out.getParent());
        Files.writeString(out, markdown);
    }

    private static String git(Path repo, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        // stderr 는 훅 로그로 — 합치면 git 경고(LF will be replaced …)가 파싱할 출력에 섞인다
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + " 실패 (종료 코드 " + p.exitValue() + ")");
        }
        return output;
    }
}
