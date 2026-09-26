package flowdiffmap.store;

import flowdiffmap.graph.Component;
import flowdiffmap.graph.Edge;
import flowdiffmap.graph.Graph;
import flowdiffmap.graph.Layer;
import flowdiffmap.graph.Node;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 커밋별 그래프 전체 스냅샷을 PostgreSQL 에 둔다.
 *
 * <p>저장은 부모 스냅샷을 SQL 로 복사한 뒤 바뀐 파일 소유 행만 갈아 끼운다 — 파싱은 바뀐 파일만 하면서도
 * 커밋마다 온전한 그래프가 남아 diff 가 두 스냅샷의 단순 비교가 된다.
 */
public class GraphStore {

    // 열 순서가 다른 옛 로컬 볼륨의 표에 값이 엇갈려 들어가지 않게 열 이름을 댄다
    private static final String COMPONENT = "commit_sha, fqn, layer, file";
    private static final String NODE = "commit_sha, id, fqn, method, layer, endpoint, body_hash, file";
    private static final String EDGE = "commit_sha, from_id, to_id, file";

    private static final Set<Layer> SPRING = EnumSet.of(Layer.CONTROLLER, Layer.SERVICE, Layer.REPOSITORY);

    private final String url;
    private final String user;
    private final String password;

    public GraphStore(String url, String user, String password) throws SQLException {
        this.url = url;
        this.user = user;
        this.password = password;
        try (InputStream schema = Objects.requireNonNull(GraphStore.class.getResourceAsStream("/schema.sql"), "schema.sql 없음");
             Connection c = connect();
             Statement st = c.createStatement()) {
            st.execute(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("schema.sql 읽기 실패", e);
        }
    }

    /**
     * 엣지는 callee 가 이 커밋의 컴포넌트인 것만 남긴다 — 저장은 소스 안 모든 클래스로의 호출을 두어서,
     * callee 파일만 바뀌어 컴포넌트가 되거나 그만두어도 안 바뀐 호출자 쪽 엣지가 맞게 읽힌다.
     * 엣지만 있고 선언이 없는 callee(상속 메서드)는 callee 컴포넌트의 레이어로 암묵 노드를 채운다.
     * Spring 컴포넌트가 있는 리포는 진입 · 내부 클래스를 버리고, 없는 리포는 진입점에서 호출로 닿는 것만 남긴다 —
     * 도달 여부는 그래프 전체를 봐야 알 수 있어 바뀐 파일만 뽑는 저장 쪽이 아니라 여기서 판정한다.
     */
    public Optional<Graph> load(String sha) throws SQLException {
        try (Connection c = connect()) {
            if (!exists(c, sha)) {
                return Optional.empty();
            }
            Map<String, Component> components = new HashMap<>();
            try (ResultSet r = query(c, "SELECT fqn, layer, file FROM component WHERE commit_sha = ?", sha)) {
                while (r.next()) {
                    components.put(r.getString(1), new Component(r.getString(1), Layer.valueOf(r.getString(2)), r.getString(3)));
                }
            }
            Map<String, Node> nodes = new HashMap<>();
            try (ResultSet r = query(c, "SELECT id, fqn, method, layer, endpoint, body_hash, file FROM node WHERE commit_sha = ?", sha)) {
                while (r.next()) {
                    nodes.put(r.getString(1), new Node(r.getString(1), r.getString(2), r.getString(3),
                            Layer.valueOf(r.getString(4)), r.getString(5), r.getString(6), r.getString(7)));
                }
            }
            boolean spring = components.values().stream().anyMatch(k -> SPRING.contains(k.layer()));
            if (spring) {
                // 진입 · 내부 클래스(@SpringBootApplication main · 엔티티 · 설정)는 그림 밖
                components.values().removeIf(k -> !SPRING.contains(k.layer()));
                nodes.values().removeIf(n -> !components.containsKey(n.fqn()));
            }
            Set<Edge> edges = new HashSet<>();
            try (ResultSet r = query(c, "SELECT from_id, to_id, file FROM edge WHERE commit_sha = ?", sha)) {
                while (r.next()) {
                    Edge e = new Edge(r.getString(1), r.getString(2), r.getString(3));
                    if (components.containsKey(fqnOf(e.from())) && components.containsKey(fqnOf(e.to()))) {
                        edges.add(e);
                    }
                }
            }
            for (Edge e : edges) {
                nodes.computeIfAbsent(e.to(), id -> {
                    Component callee = components.get(fqnOf(id));
                    String method = id.substring(id.indexOf('#') + 1, id.lastIndexOf('/'));
                    // 진입 클래스의 상속 메서드는 진입점이 아니다
                    Layer layer = callee.layer() == Layer.ENTRY ? Layer.INTERNAL : callee.layer();
                    return new Node(id, callee.fqn(), method, layer, null, "", callee.file());
                });
            }
            if (!spring) {
                Set<String> reached = reachableFromEntries(nodes, edges);
                nodes.keySet().retainAll(reached);
                edges.removeIf(e -> !reached.contains(e.from()));
                Set<String> fqns = nodes.values().stream().map(Node::fqn).collect(Collectors.toSet());
                components.keySet().retainAll(fqns);
            }
            return Optional.of(new Graph(nodes, edges, Set.copyOf(components.values())));
        }
    }

    /** ENTRY 노드에서 엣지를 따라 닿는 노드 id — 순환은 방문 집합이 끊는다. */
    private static Set<String> reachableFromEntries(Map<String, Node> nodes, Set<Edge> edges) {
        Map<String, List<String>> callees = edges.stream()
                .collect(Collectors.groupingBy(Edge::from, Collectors.mapping(Edge::to, Collectors.toList())));
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        nodes.values().stream().filter(n -> n.layer() == Layer.ENTRY).forEach(n -> queue.add(n.id()));
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (reached.add(id)) {
                queue.addAll(callees.getOrDefault(id, List.of()));
            }
        }
        return reached;
    }

    public boolean has(String sha) throws SQLException {
        try (Connection c = connect()) {
            return exists(c, sha);
        }
    }

    /** 부모 없이 {@code g} 만으로 — 첫 실행 · 루트 커밋의 베이스라인. */
    public void saveFull(String sha, Graph g) throws SQLException {
        saveIncremental(null, sha, Set.of(), g);
    }

    /**
     * 부모 스냅샷에서 {@code touchedFiles}(변경 · 삭제) 소유 행을 뺀 나머지를 복사하고 {@code fresh} 를 더한다.
     * 한 트랜잭션 · 같은 {@code sha} 로 다시 부르면 덮어쓴다.
     *
     * @param parentSha    스냅샷이 있어야 한다 — 없으면 바뀐 파일만 담긴 스냅샷이 온전한 것처럼 남아서 예외
     * @param touchedFiles {@link Node#file()} 과 같은 형식 — 소스 루트 기준 상대 경로 · {@code /} 구분
     */
    public void saveIncremental(String parentSha, String sha, Set<String> touchedFiles, Graph fresh) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            if (parentSha != null && !exists(c, parentSha)) {
                throw new IllegalStateException("부모 스냅샷 없음: " + parentSha);
            }
            for (String table : new String[] {"snapshot", "component", "node", "edge"}) {
                update(c, "DELETE FROM " + table + " WHERE commit_sha = ?", sha);
            }
            update(c, "INSERT INTO snapshot (commit_sha) VALUES (?)", sha);

            Array touched = c.createArrayOf("text", touchedFiles.toArray());
            update(c, "INSERT INTO component (" + COMPONENT + ") SELECT ?, fqn, layer, file FROM component"
                    + " WHERE commit_sha = ? AND file <> ALL(?)", sha, parentSha, touched);
            update(c, "INSERT INTO node (" + NODE + ") SELECT ?, id, fqn, method, layer, endpoint, body_hash, file FROM node"
                    + " WHERE commit_sha = ? AND file <> ALL(?)", sha, parentSha, touched);
            update(c, "INSERT INTO edge (" + EDGE + ") SELECT ?, from_id, to_id, file FROM edge"
                    + " WHERE commit_sha = ? AND file <> ALL(?)", sha, parentSha, touched);

            insert(c, sha, fresh);
            c.commit();
        }
    }

    private static void insert(Connection c, String sha, Graph g) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO component (" + COMPONENT + ") VALUES (?, ?, ?, ?)")) {
            for (Component k : g.components()) {
                bind(ps, sha, k.fqn(), k.layer().name(), k.file()).addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO node (" + NODE + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Node n : g.nodes().values()) {
                bind(ps, sha, n.id(), n.fqn(), n.method(), n.layer().name(), n.endpoint(), n.bodyHash(), n.file()).addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO edge (" + EDGE + ") VALUES (?, ?, ?, ?)")) {
            for (Edge e : g.edges()) {
                bind(ps, sha, e.from(), e.to(), e.file()).addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String fqnOf(String nodeId) {
        return nodeId.substring(0, nodeId.indexOf('#'));
    }

    private static boolean exists(Connection c, String sha) throws SQLException {
        try (ResultSet r = query(c, "SELECT 1 FROM snapshot WHERE commit_sha = ?", sha)) {
            return r.next();
        }
    }

    /** 결과셋을 닫으면 문장도 같이 닫힌다. */
    private static ResultSet query(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = bind(c.prepareStatement(sql), args);
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    private static void update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = bind(c.prepareStatement(sql), args)) {
            ps.executeUpdate();
        }
    }

    private static PreparedStatement bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        return ps;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
