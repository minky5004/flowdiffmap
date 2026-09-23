package flowdiffmap.render;

import flowdiffmap.graph.Edge;
import flowdiffmap.graph.Graph;
import flowdiffmap.graph.GraphDiff;
import flowdiffmap.graph.Layer;
import flowdiffmap.graph.Node;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 두 스냅샷의 diff 를 칠한 Mermaid 흐름도 + 변경 표 마크다운.
 *
 * <p>노드 · 엣지는 id 순으로 늘어놓는다 — 같은 그래프면 같은 파일이 나와서 출력 파일의 git diff 가 실제 변화만 보인다.
 */
public final class MermaidRenderer {

    private static final Comparator<Edge> EDGE_ORDER = Comparator.comparing(Edge::from).thenComparing(Edge::to);

    private MermaidRenderer() {
    }

    /**
     * @param before null 이면 베이스라인 — 하이라이트 없이 {@code after} 만
     * @param labels 노드 id → LLM 이 붙인 이름 · 삭제 노드 몫은 부모 커밋 라벨로 · 없는 노드는 {@code 클래스.메서드}
     */
    public static String render(Graph before, Graph after, GraphDiff diff, Map<String, String> labels, String shortSha) {
        Map<String, Node> nodes = new HashMap<>(after.nodes());
        diff.removedNodes().forEach(id -> nodes.put(id, before.nodes().get(id)));
        TreeSet<Edge> edges = new TreeSet<>(EDGE_ORDER);
        edges.addAll(after.edges());
        edges.addAll(diff.removedEdges());

        StringBuilder md = new StringBuilder("# 요청 흐름 · `" + shortSha + "`\n\n```mermaid\nflowchart LR\n");
        Map<String, String> ids = new HashMap<>();
        for (Layer layer : Layer.values()) {
            List<Node> inLayer = nodes.values().stream().filter(n -> n.layer() == layer)
                    .sorted(Comparator.comparing(Node::id)).toList();
            if (inLayer.isEmpty()) {
                continue;
            }
            md.append("  subgraph ").append(layer).append("[\"").append(title(layer)).append("\"]\n");
            for (Node n : inLayer) {
                String id = "n" + ids.size();
                ids.put(n.id(), id);
                md.append("    ").append(id).append("[\"").append(escape(text(n, labels))).append("\"]")
                        .append(style(n.id(), diff)).append('\n');
            }
            md.append("  end\n");
        }
        List<String> linkStyles = new ArrayList<>();
        int i = 0;
        for (Edge e : edges) {
            md.append("    ").append(ids.get(e.from())).append(" --> ").append(ids.get(e.to())).append('\n');
            if (diff.addedEdges().contains(e)) {
                linkStyles.add("  linkStyle " + i + " stroke:#2a2,stroke-width:2px");
            } else if (diff.removedEdges().contains(e)) {
                linkStyles.add("  linkStyle " + i + " stroke:#d33,stroke-width:2px,stroke-dasharray:4");
            }
            i++;
        }
        linkStyles.forEach(s -> md.append(s).append('\n'));
        if (!diff.isEmpty()) {
            md.append("  classDef added fill:#dfd,stroke:#2a2\n")
                    .append("  classDef removed fill:#fdd,stroke:#d33,stroke-dasharray:4\n")
                    .append("  classDef changed fill:#fe8,stroke:#c90\n");
        }
        md.append("```\n\n");
        appendTable(md, before, diff, labels, nodes);
        return md.toString();
    }

    private static void appendTable(StringBuilder md, Graph before, GraphDiff diff,
                                    Map<String, String> labels, Map<String, Node> nodes) {
        if (diff.isEmpty()) {
            md.append(before == null ? "첫 스냅샷 · 비교할 부모 없음\n" : "이번 커밋에서 바뀐 흐름 없음\n");
            return;
        }
        md.append("| 구분 | 대상 |\n|---|---|\n");
        row(md, "추가", diff.addedNodes().stream().sorted().map(id -> cell(nodes.get(id), labels)).toList());
        row(md, "삭제", diff.removedNodes().stream().sorted().map(id -> cell(nodes.get(id), labels)).toList());
        row(md, "변경", diff.changedNodes().stream().sorted().map(id -> cell(nodes.get(id), labels)).toList());
        row(md, "호출 추가", diff.addedEdges().stream().sorted(EDGE_ORDER).map(e -> arrow(e, nodes, labels)).toList());
        row(md, "호출 삭제", diff.removedEdges().stream().sorted(EDGE_ORDER).map(e -> arrow(e, nodes, labels)).toList());
    }

    private static void row(StringBuilder md, String kind, List<String> targets) {
        targets.forEach(t -> md.append("| ").append(kind).append(" | ").append(t).append(" |\n"));
    }

    private static String arrow(Edge e, Map<String, Node> nodes, Map<String, String> labels) {
        return cell(nodes.get(e.from()), labels) + " → " + cell(nodes.get(e.to()), labels);
    }

    private static String cell(Node n, Map<String, String> labels) {
        return labels.getOrDefault(n.id(), signature(n)).replace("|", "\\|");
    }

    private static String text(Node n, Map<String, String> labels) {
        String name = labels.getOrDefault(n.id(), signature(n));
        return n.endpoint() == null ? name : n.endpoint() + "<br/>" + name;
    }

    private static String signature(Node n) {
        return n.fqn().substring(n.fqn().lastIndexOf('.') + 1) + "." + n.method();
    }

    private static String style(String id, GraphDiff diff) {
        if (diff.addedNodes().contains(id)) {
            return ":::added";
        }
        if (diff.removedNodes().contains(id)) {
            return ":::removed";
        }
        return diff.changedNodes().contains(id) ? ":::changed" : "";
    }

    private static String escape(String s) {
        return s.replace("\"", "#quot;");
    }

    private static String title(Layer layer) {
        return layer.name().charAt(0) + layer.name().substring(1).toLowerCase();
    }
}
