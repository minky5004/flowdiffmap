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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 커밋별 그래프 전체 스냅샷을 PostgreSQL 에 둔다.
 *
 * <p>저장은 부모 스냅샷을 SQL 로 복사한 뒤 바뀐 파일 소유 행만 갈아 끼운다 — 파싱은 바뀐 파일만 하면서도
 * 커밋마다 온전한 그래프가 남아 diff 가 두 스냅샷의 단순 비교가 된다.
 */
public class GraphStore {

    private final String url;
    private final String user;
    private final String password;

    public GraphStore(String url, String user, String password) throws SQLException {
        this.url = url;
        this.user = user;
        this.password = password;
        try (Connection c = connect(); InputStream schema = GraphStore.class.getResourceAsStream("/schema.sql")) {
            c.createStatement().execute(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("schema.sql 읽기 실패", e);
        }
    }

    /** 엣지만 있고 선언이 없는 callee(상속 메서드)는 callee 컴포넌트의 레이어로 암묵 노드를 채운다. */
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
            Set<Edge> edges = new HashSet<>();
            try (ResultSet r = query(c, "SELECT from_id, to_id, file FROM edge WHERE commit_sha = ?", sha)) {
                while (r.next()) {
                    edges.add(new Edge(r.getString(1), r.getString(2), r.getString(3)));
                }
            }
            for (Edge e : edges) {
                // 저장 시 prune 이 컴포넌트가 아닌 callee 엣지를 지워 두어 callee 컴포넌트는 항상 있다
                nodes.computeIfAbsent(e.to(), id -> {
                    Component callee = components.get(id.substring(0, id.indexOf('#')));
                    String method = id.substring(id.indexOf('#') + 1, id.lastIndexOf('/'));
                    return new Node(id, callee.fqn(), method, callee.layer(), null, "", callee.file());
                });
            }
            return Optional.of(new Graph(nodes, edges, Set.copyOf(components.values())));
        }
    }

    /** 부모 없이 {@code g} 만으로 — 첫 실행 · 루트 커밋의 베이스라인. */
    public void saveFull(String sha, Graph g) throws SQLException {
        saveIncremental(null, sha, Set.of(), g);
    }

    /**
     * 부모 스냅샷에서 {@code touchedFiles}(변경 · 삭제) 소유 행을 뺀 나머지를 복사하고 {@code fresh} 를 더한다.
     * 한 트랜잭션 · 같은 {@code sha} 로 다시 부르면 덮어쓴다.
     */
    public void saveIncremental(String parentSha, String sha, Set<String> touchedFiles, Graph fresh) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            for (String table : new String[] {"snapshot", "component", "node", "edge"}) {
                update(c, "DELETE FROM " + table + " WHERE commit_sha = ?", sha);
            }
            update(c, "INSERT INTO snapshot VALUES (?)", sha);

            Array touched = c.createArrayOf("text", touchedFiles.toArray());
            update(c, "INSERT INTO component SELECT ?, fqn, layer, file FROM component"
                    + " WHERE commit_sha = ? AND file <> ALL(?)", sha, parentSha, touched);
            update(c, "INSERT INTO node SELECT ?, id, fqn, method, layer, endpoint, body_hash, label, file FROM node"
                    + " WHERE commit_sha = ? AND file <> ALL(?)", sha, parentSha, touched);
            update(c, "INSERT INTO edge SELECT ?, from_id, to_id, file FROM edge"
                    + " WHERE commit_sha = ? AND file <> ALL(?)", sha, parentSha, touched);

            insert(c, sha, fresh);
            // 바뀐 파일 안에서도 선언이 그대로인 노드는 부모 라벨을 이어받는다 — LLM 에 다시 보내지 않게
            update(c, "UPDATE node n SET label = p.label FROM node p"
                    + " WHERE n.commit_sha = ? AND p.commit_sha = ? AND p.id = n.id AND p.body_hash = n.body_hash"
                    + " AND n.label IS NULL", sha, parentSha);
            // callee 클래스가 삭제됐거나 어노테이션이 빠진 엣지 — 호출하는 쪽 파일이 안 바뀌어 복사로 딸려 온 것
            update(c, "DELETE FROM edge e WHERE e.commit_sha = ? AND NOT EXISTS (SELECT 1 FROM component k"
                    + " WHERE k.commit_sha = e.commit_sha AND k.fqn = split_part(e.to_id, '#', 1))", sha);
            c.commit();
        }
    }

    public void updateLabels(String sha, Map<String, String> labels) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("UPDATE node SET label = ? WHERE commit_sha = ? AND id = ?")) {
            for (var e : labels.entrySet()) {
                bind(ps, e.getValue(), sha, e.getKey()).addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 라벨이 붙은 노드만. */
    public Map<String, String> labels(String sha) throws SQLException {
        Map<String, String> labels = new HashMap<>();
        try (Connection c = connect();
             ResultSet r = query(c, "SELECT id, label FROM node WHERE commit_sha = ? AND label IS NOT NULL", sha)) {
            while (r.next()) {
                labels.put(r.getString(1), r.getString(2));
            }
        }
        return labels;
    }

    private static void insert(Connection c, String sha, Graph g) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO component VALUES (?, ?, ?, ?)")) {
            for (Component k : g.components()) {
                bind(ps, sha, k.fqn(), k.layer().name(), k.file()).addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO node VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)")) {
            for (Node n : g.nodes().values()) {
                bind(ps, sha, n.id(), n.fqn(), n.method(), n.layer().name(), n.endpoint(), n.bodyHash(), n.file()).addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO edge VALUES (?, ?, ?, ?)")) {
            for (Edge e : g.edges()) {
                bind(ps, sha, e.from(), e.to(), e.file()).addBatch();
            }
            ps.executeBatch();
        }
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
