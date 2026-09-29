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
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * post-commit 훅이 부르는 진입점 — HEAD 의 스냅샷을 저장하고, 부모 대비 흐름이 바뀌었으면
 * {@code docs/flow/request-flow.md} 에 쓴다. 결과 파일은 커밋하지 않는다(훅이 커밋하면 다시 훅이 돈다).
 */
public final class Main {

    /** 모듈 접두(그룹 1 · 루트 모듈은 없음) + 소스 루트 아래 상대 경로(그룹 2) — 멀티모듈은 디렉터리 관례로만 찾는다. */
    private static final Pattern SOURCE = Pattern.compile("^(.*/)?src/main/java/(.+\\.java)$");
    private static final Set<String> BUILD_FILES = Set.of("build.gradle", "build.gradle.kts", "pom.xml");
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
            Set<String> modules = checkout(repo, sha, tree);

            if (parent == null || !store.has(parent)) {
                // 첫 실행 · 루트 커밋 · DB 가 꺼져 있던 커밋 뒤 — 비교할 부모가 없으니 전체를 베이스라인으로
                Graph all = modules.isEmpty() ? EMPTY : new FlowExtractor(tree, modules).extract(javaFiles(tree));
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
                Matcher m = SOURCE.matcher(path);
                if (!m.matches()) {
                    continue;
                }
                touched.add(Objects.toString(m.group(1), "") + m.group(2));
                if (!diff[i].equals("D")) {
                    changed.add(tree.resolve(path));
                }
            }
            Graph fresh = EMPTY;
            if (!changed.isEmpty()) {
                FlowExtractor extractor = new FlowExtractor(tree, modules);
                fresh = extractor.extract(changed);
                // 문법 오류 중인 파일은 부모 행을 그대로 — 지우면 그 파일 메서드가 전부 삭제로 칠해진다
                Set<String> unparsed = extractor.unparsed();
                touched.removeAll(unparsed);

                // 바뀐 파일을 부르는 파일도 다시 — 호출자 엣지의 대상 id 가 피호출 시그니처(파라미터 타입)를 담아서,
                // 부모 행을 복사하면 시그니처만 바뀐 커밋에 사라진 id 를 가리킨다. 읽지 못한 파일의 호출자는 빼고
                // 부모 행을 둔다 — 솔버가 그 파일을 못 풀어 호출자 엣지가 통째로 빠진다
                Set<String> callers = new HashSet<>(store.callerFiles(parent, touched));
                callers.removeAll(store.callerFiles(parent, unparsed));
                callers.removeAll(unparsed);
                if (!callers.isEmpty()) {
                    touched.addAll(callers);
                    callers.forEach(caller -> changed.add(extractor.resolve(caller)));
                    fresh = extractor.extract(changed);
                }
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

    /** 커밋 {@code sha} 의 모든 {@code src/main/java} 아래 {@code .java} 를 {@code dir} 에 같은 경로로 풀고 모듈 접두 목록을 돌려준다. */
    private static Set<String> checkout(Path repo, String sha, Path dir) throws IOException, InterruptedException {
        // ls-tree -z: "모드 blob 오브젝트id\t경로\0"
        List<String[]> blobs = new ArrayList<>();
        Set<String> sourceDirs = new TreeSet<>();
        Set<String> buildDirs = new HashSet<>();
        // ls-tree 는 glob pathspec 을 못 받는다 — 전체 목록을 받아 거른다
        for (String entry : git(repo, "ls-tree", "-r", "-z", sha).split("\0")) {
            int tab = entry.indexOf('\t');
            String[] meta = entry.substring(0, Math.max(tab, 0)).split(" ");
            String path = entry.substring(tab + 1);
            Matcher m = SOURCE.matcher(path);
            if (meta.length == 3 && meta[1].equals("blob") && m.matches()) {
                blobs.add(new String[] {meta[2], path});
                sourceDirs.add(Objects.toString(m.group(1), ""));
            } else if (meta.length == 3 && BUILD_FILES.contains(path.substring(path.lastIndexOf('/') + 1))) {
                buildDirs.add(path.substring(0, path.lastIndexOf('/') + 1));
            }
        }
        // 루트는 빌드 파일 없이도 소스 루트(지금까지의 동작) · 그 밖은 빌드 파일 옆이어야 모듈 — 예제 · 픽스처 폴더가 안 섞이게
        Set<String> modules = new TreeSet<>(sourceDirs);
        modules.removeIf(m -> !m.isEmpty() && !buildDirs.contains(m));
        blobs.removeIf(b -> !modules.contains(moduleOf(b[1])));
        if (blobs.isEmpty()) {
            return modules;
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
        return modules;
    }

    private static String moduleOf(String path) {
        Matcher m = SOURCE.matcher(path);
        return m.matches() ? Objects.toString(m.group(1), "") : "";
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

    /** 임시 폴더에는 소스 루트 아래 파일만 풀려 있어 전부 대상이다. */
    private static List<Path> javaFiles(Path tree) throws IOException {
        try (Stream<Path> files = Files.walk(tree)) {
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
