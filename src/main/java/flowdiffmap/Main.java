package flowdiffmap;

import flowdiffmap.ast.FlowExtractor;
import flowdiffmap.graph.Graph;
import flowdiffmap.graph.GraphDiff;
import flowdiffmap.render.MermaidRenderer;
import flowdiffmap.store.GraphStore;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * post-commit 훅이 부르는 진입점 — HEAD 의 스냅샷을 저장하고, 부모 대비 흐름이 바뀌었으면
 * {@code docs/flow/request-flow.md} 에 쓴다. 결과 파일은 커밋하지 않는다(훅이 커밋하면 다시 훅이 돈다).
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
        // rev-list --parents: "HEAD 부모1 부모2…" · 루트 커밋이면 HEAD 하나 · 머지는 첫 부모 기준
        String[] shas = git(repo, "rev-list", "--parents", "-n", "1", "HEAD").strip().split(" ");
        String sha = shas[0];
        String parent = shas.length > 1 ? shas[1] : null;
        String shortSha = sha.substring(0, 7);

        // 작업 폴더가 아니라 커밋된 내용을 읽는다 — 부분 커밋(git add -p) · 추적 안 된 파일이 스냅샷에 섞이면
        // 그 파일이 다시 바뀔 때까지 틀린 행이 다음 스냅샷으로 계속 복사된다
        Path tree = Files.createTempDirectory("flowdiffmap-");
        try {
            checkout(repo, sha, tree);
            Path srcRoot = tree.resolve(SRC);

            if (parent == null || !store.has(parent)) {
                // 첫 실행 · 루트 커밋 · DB 가 꺼져 있던 커밋 뒤 — 비교할 부모가 없으니 전체를 베이스라인으로
                Graph all = Files.isDirectory(srcRoot) ? new FlowExtractor(srcRoot).extract(javaFiles(srcRoot)) : EMPTY;
                store.saveFull(sha, all);
                write(repo, MermaidRenderer.render(null, store.load(sha).orElseThrow(), shortSha));
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
                    changed.add(tree.resolve(path));
                }
            }
            Graph fresh = EMPTY;
            if (!changed.isEmpty()) {
                FlowExtractor extractor = new FlowExtractor(srcRoot);
                fresh = extractor.extract(changed);
                // 문법 오류 중인 파일은 부모 행을 그대로 — 지우면 그 파일 메서드가 전부 삭제로 칠해진다
                touched.removeAll(extractor.unparsed());
            }
            store.saveIncremental(parent, sha, touched, fresh);
            if (touched.isEmpty()) {
                return;
            }
            Graph before = store.load(parent).orElseThrow();
            Graph after = store.load(sha).orElseThrow();
            // 흐름이 그대로인 커밋(docs · DTO 만)은 파일을 두어 직전 흐름 변경의 하이라이트를 남긴다
            if (!GraphDiff.between(before, after).isEmpty()) {
                write(repo, MermaidRenderer.render(before, after, shortSha));
            }
        } finally {
            try (Stream<Path> files = Files.walk(tree)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** 커밋 {@code sha} 의 {@code src/main/java} 아래 {@code .java} 를 {@code dir} 에 같은 경로로 푼다. */
    private static void checkout(Path repo, String sha, Path dir) throws IOException, InterruptedException {
        // ls-tree -z: "모드 blob 오브젝트id\t경로\0"
        List<String[]> blobs = new ArrayList<>();
        for (String entry : git(repo, "ls-tree", "-r", "-z", sha, "--", SRC).split("\0")) {
            int tab = entry.indexOf('\t');
            String[] meta = entry.substring(0, Math.max(tab, 0)).split(" ");
            String path = entry.substring(tab + 1);
            if (meta.length == 3 && meta[1].equals("blob") && path.endsWith(".java")) {
                blobs.add(new String[] {meta[2], path});
            }
        }
        if (blobs.isEmpty()) {
            return;
        }
        // cat-file --batch 한 프로세스로 전부 — 파일마다 git show 를 띄우면 수백 개 리포에서 커밋이 몇 초씩 늦는다
        Process p = new ProcessBuilder("git", "cat-file", "--batch").directory(repo.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        // 요청을 다 쓰기 전에 응답 파이프가 차면 서로를 기다리므로 쓰기는 따로
        Thread writer = Thread.ofVirtual().start(() -> {
            try (OutputStream in = p.getOutputStream()) {
                for (String[] blob : blobs) {
                    in.write((blob[0] + "\n").getBytes(StandardCharsets.US_ASCII));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try (InputStream out = new BufferedInputStream(p.getInputStream())) {
            for (String[] blob : blobs) {
                // 응답: "오브젝트id blob 크기\n" + 내용 + "\n"
                String header = line(out);
                long size = Long.parseLong(header.substring(header.lastIndexOf(' ') + 1));
                Path file = dir.resolve(blob[1]);
                Files.createDirectories(file.getParent());
                Files.write(file, out.readNBytes(Math.toIntExact(size)));
                out.read();
            }
        }
        writer.join();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git cat-file --batch 실패 (종료 코드 " + p.exitValue() + ")");
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int b = in.read(); b != '\n'; b = in.read()) {
            if (b < 0) {
                throw new IOException("git cat-file 응답이 중간에 끊김");
            }
            bytes.write(b);
        }
        return bytes.toString(StandardCharsets.UTF_8);
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
