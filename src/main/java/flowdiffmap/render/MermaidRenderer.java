package flowdiffmap.render;

import flowdiffmap.graph.Edge;
import flowdiffmap.graph.Graph;
import flowdiffmap.graph.GraphDiff;
import flowdiffmap.graph.Layer;
import flowdiffmap.graph.Node;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 두 스냅샷의 diff 를 칠한 Mermaid 흐름도 + 변경 표 마크다운.
 *
 * <p>Mermaid id 는 노드 id 에서 만들고 노드 · 엣지는 id 순으로 늘어놓는다 — 노드 하나가 늘어도 다른 줄은 그대로라
 * 출력 파일의 git diff 가 실제 변화 근처에만 생긴다. 엣지 색은 Mermaid 가 순번으로만 받아서 {@code linkStyle} 번호는 밀린다.
 */
public final class MermaidRenderer {

    private static final Comparator<Edge> EDGE_ORDER = Comparator.comparing(Edge::from).thenComparing(Edge::to);

    private MermaidRenderer() {
    }

    /**
     * @param before null 이면 베이스라인 — 하이라이트 없이 {@code after} 만
     * @param after  {@code GraphStore.load} 결과 — 엣지 양 끝이 다 노드여야 한다(암묵 노드가 채워진 상태)
     */
    public static String render(Graph before, Graph after, String shortSha) {
        GraphDiff diff = GraphDiff.between(before, after);
        Map<String, Node> nodes = new HashMap<>(after.nodes());
        diff.removedNodes().forEach(id -> nodes.put(id, before.nodes().get(id)));
        TreeSet<Edge> edges = new TreeSet<>(EDGE_ORDER);
        edges.addAll(after.edges());
        edges.addAll(diff.removedEdges());
        Function<Node, String> name = namer(nodes.values());

        // 기능 rollup 은 그림(subgraph 배치)과 표가 같은 판정을 공유 — 여기서 한 번만 계산
        Set<String> claimedNodes = new HashSet<>();
        Set<Edge> claimedEdges = new HashSet<>();
        List<Feature> featureAdded = featureEntries(diff.addedNodes(), diff.addedEdges(), nodes, claimedNodes, claimedEdges);
        List<Feature> featureRemoved = featureEntries(diff.removedNodes(), diff.removedEdges(), nodes, claimedNodes, claimedEdges);

        StringBuilder md = new StringBuilder("# 요청 흐름 · `" + shortSha + "`\n\n```mermaid\nflowchart LR\n");
        boolean hasAdded = !declared(diff.addedNodes(), nodes).isEmpty();
        boolean hasRemoved = !declared(diff.removedNodes(), nodes).isEmpty();
        boolean hasChanged = !declared(diff.changedNodes(), nodes).isEmpty();
        if (hasAdded || hasRemoved || hasChanged) {
            md.append("  subgraph LEGEND[\"범례\"]\n");
            if (hasAdded) {
                md.append("    legend_added[\"노드 추가\"]:::added\n");
            }
            if (hasRemoved) {
                md.append("    legend_removed[\"노드 삭제\"]:::removed\n");
            }
            if (hasChanged) {
                md.append("    legend_changed[\"노드 변경\"]:::changed\n");
            }
            md.append("  end\n");
        }
        for (Feature f : featureAdded) {
            featureSubgraph(md, f, name, diff);
        }
        for (Feature f : featureRemoved) {
            featureSubgraph(md, f, name, diff);
        }
        for (Layer layer : Layer.values()) {
            List<Node> inLayer = nodes.values().stream()
                    .filter(n -> n.layer() == layer && !claimedNodes.contains(n.id()))
                    .sorted(Comparator.comparing(Node::id)).toList();
            if (inLayer.isEmpty()) {
                continue;
            }
            md.append("  subgraph ").append(layer).append("[\"").append(title(layer)).append("\"]\n");
            inLayer.forEach(n -> nodeLine(md, n, name, diff));
            md.append("  end\n");
        }
        StringBuilder linkStyles = new StringBuilder();
        int i = 0;
        for (Edge e : edges) {
            md.append("    ").append(mermaidId(e.from())).append(" --> ").append(mermaidId(e.to())).append('\n');
            if (diff.addedEdges().contains(e)) {
                linkStyles.append("  linkStyle ").append(i).append(" stroke:#2a2,stroke-width:2px\n");
            } else if (diff.removedEdges().contains(e)) {
                linkStyles.append("  linkStyle ").append(i).append(" stroke:#d33,stroke-width:2px,stroke-dasharray:4\n");
            }
            i++;
        }
        md.append(linkStyles);
        if (!diff.isEmpty()) {
            md.append("  classDef added fill:#dfd,stroke:#2a2\n")
                    .append("  classDef removed fill:#fdd,stroke:#d33,stroke-dasharray:4\n")
                    .append("  classDef changed fill:#fe8,stroke:#c90\n");
        }
        md.append("```\n\n");

        if (diff.isEmpty()) {
            return md.append(before == null ? "첫 스냅샷 · 비교할 부모 없음\n" : "이번 커밋에서 바뀐 흐름 없음\n").toString();
        }
        Function<Edge, String> arrow = e -> name.apply(nodes.get(e.from())) + " → " + name.apply(nodes.get(e.to()));
        md.append("| 구분 | 대상 |\n|---|---|\n");
        featureRows(md, "기능 추가", featureAdded, name);
        nodeRows(md, "추가", excludingClaimed(declared(diff.addedNodes(), nodes), claimedNodes), name);
        featureRows(md, "기능 삭제", featureRemoved, name);
        nodeRows(md, "삭제", excludingClaimed(declared(diff.removedNodes(), nodes), claimedNodes), name);
        nodeRows(md, "변경", declared(diff.changedNodes(), nodes), name);
        edgeRows(md, "호출 추가", excludingClaimed(diff.addedEdges(), claimedEdges), arrow);
        edgeRows(md, "호출 삭제", excludingClaimed(diff.removedEdges(), claimedEdges), arrow);
        return md.toString();
    }

    /** 엔드포인트 또는 진입점 하나 · 거기에만 속하는 다운스트림 노드 전부 — 그림의 기능 subgraph 와 표의 "기능 추가/삭제" 행이 같이 씀. */
    private record Feature(Node entry, List<Node> members) {
    }

    /** 엔드포인트가 있으면 그대로 · 진입점(main · 리스너 콜백)은 {@code 클래스.메서드} — 같은 콜백 이름을 가진 리스너끼리 구분되게. */
    private static String label(Feature f, Function<Node, String> name) {
        return f.entry().endpoint() != null ? escape(f.entry().endpoint()) : name.apply(f.entry());
    }

    private static void featureSubgraph(StringBuilder md, Feature f, Function<Node, String> name, GraphDiff diff) {
        md.append("  subgraph F_").append(mermaidId(f.entry().id())).append("[\"")
                .append(label(f, name)).append("\"]\n");
        f.members().forEach(n -> nodeLine(md, n, name, diff));
        md.append("  end\n");
    }

    private static void nodeLine(StringBuilder md, Node n, Function<Node, String> name, GraphDiff diff) {
        String text = name.apply(n);
        md.append("    ").append(mermaidId(n.id())).append("[\"")
                .append(n.endpoint() == null ? text : escape(n.endpoint()) + "<br/>" + text).append("\"]")
                .append(style(n, diff)).append('\n');
    }

    /**
     * 새로 생기거나 없어진 엔드포인트 · 진입점에서 거기에만 속하는 다운스트림 노드 · 엣지를 하나로 묶는다.
     * 다른 엔드포인트로 이어지는 노드는 절대 흡수하지 않고, 그 밖의 노드는 들어오는 호출이 전부 이미 이 묶음
     * 안에서 오는 것일 때만(고정점까지 반복) 흡수한다 — 같은 흐름 안에서 두 갈래로 만나는 노드(다이아몬드)는
     * 묶이고, 바깥(다른 흐름 · 손 안 댄 기존 코드)에서도 호출이 들어오는 진짜 공유 노드는 개별 행으로 남는다.
     * 라벨은 {@link #label}.
     */
    private static List<Feature> featureEntries(Set<String> nodeIds, Set<Edge> edgeSet, Map<String, Node> nodes,
            Set<String> claimedNodes, Set<Edge> claimedEdges) {
        Map<String, List<Edge>> byFrom = edgeSet.stream().collect(Collectors.groupingBy(Edge::from));
        Map<String, List<Edge>> byTo = edgeSet.stream().collect(Collectors.groupingBy(Edge::to));

        List<Node> entries = nodeIds.stream().sorted().map(nodes::get)
                .filter(n -> (n.layer() == Layer.CONTROLLER && n.endpoint() != null || n.layer() == Layer.ENTRY) && declared(n))
                .toList();
        Set<String> entryIds = entries.stream().map(Node::id).collect(Collectors.toSet());

        List<Feature> result = new ArrayList<>();
        for (Node entry : entries) {
            Set<String> component = new LinkedHashSet<>();
            component.add(entry.id());
            boolean grown = true;
            while (grown) {
                grown = false;
                for (String candidate : nodeIds) {
                    if (component.contains(candidate) || entryIds.contains(candidate)) {
                        continue;
                    }
                    List<Edge> incoming = byTo.getOrDefault(candidate, List.of());
                    if (!incoming.isEmpty() && incoming.stream().allMatch(e -> component.contains(e.from()))) {
                        component.add(candidate);
                        grown = true;
                    }
                }
            }
            claimedNodes.addAll(component);
            for (String id : component) {
                byFrom.getOrDefault(id, List.of()).stream().filter(e -> component.contains(e.to()))
                        .forEach(claimedEdges::add);
            }
            result.add(new Feature(entry, component.stream().sorted().map(nodes::get).toList()));
        }
        return result;
    }

    private static void featureRows(StringBuilder md, String kind, List<Feature> features, Function<Node, String> name) {
        features.forEach(f -> md.append("| ").append(kind).append(" | ").append(label(f, name)).append(" |\n"));
    }

    private static List<Node> excludingClaimed(List<Node> targets, Set<String> claimed) {
        return targets.stream().filter(n -> !claimed.contains(n.id())).toList();
    }

    private static Set<Edge> excludingClaimed(Set<Edge> edges, Set<Edge> claimed) {
        return edges.stream().filter(e -> !claimed.contains(e)).collect(Collectors.toSet());
    }

    /** {@code 클래스.메서드} · 오버로드가 있는 메서드만 인자 수를 붙인다 — 두 {@code find} 상자가 구분되게. */
    private static Function<Node, String> namer(Collection<Node> nodes) {
        Map<String, Long> overloads = nodes.stream()
                .collect(Collectors.groupingBy(n -> n.fqn() + "#" + n.method(), Collectors.counting()));
        return n -> n.fqn().substring(n.fqn().lastIndexOf('.') + 1) + "." + n.method()
                + (overloads.get(n.fqn() + "#" + n.method()) > 1 ? "/" + n.id().substring(n.id().lastIndexOf('/') + 1) : "");
    }

    /**
     * 암묵 노드(상속 메서드 · {@code bodyHash} 빈 문자열)는 호출이 생기고 사라질 때 같이 생기고 사라진다 —
     * 메서드가 추가 · 삭제된 것이 아니라서 칠하지 않고 엣지 색만 남긴다.
     */
    private static boolean declared(Node n) {
        return !n.bodyHash().isEmpty();
    }

    private static List<Node> declared(Set<String> ids, Map<String, Node> nodes) {
        return ids.stream().sorted().map(nodes::get).filter(MermaidRenderer::declared).toList();
    }

    private static void nodeRows(StringBuilder md, String kind, List<Node> targets, Function<Node, String> name) {
        targets.forEach(n -> md.append("| ").append(kind).append(" | ").append(name.apply(n)).append(" |\n"));
    }

    private static void edgeRows(StringBuilder md, String kind, Set<Edge> edges, Function<Edge, String> arrow) {
        edges.stream().sorted(EDGE_ORDER)
                .forEach(e -> md.append("| ").append(kind).append(" | ").append(arrow.apply(e)).append(" |\n"));
    }

    private static String style(Node n, GraphDiff diff) {
        if (!declared(n)) {
            return "";
        }
        if (diff.addedNodes().contains(n.id())) {
            return ":::added";
        }
        if (diff.removedNodes().contains(n.id())) {
            return ":::removed";
        }
        return diff.changedNodes().contains(n.id()) ? ":::changed" : "";
    }

    /** {@code shop.order.OrderService#find/1} → {@code shop_order_OrderService_find_1}. */
    private static String mermaidId(String nodeId) {
        return nodeId.replaceAll("[^A-Za-z0-9]", "_");
    }

    /** 엔드포인트는 소스의 문자열 리터럴 그대로라 {@code "} 는 노드 문법을 깨고 {@code <>} 는 HTML 태그로 먹힌다. */
    private static String escape(String s) {
        return s.replace("\"", "#quot;").replace("<", "#lt;").replace(">", "#gt;");
    }

    private static String title(Layer layer) {
        return switch (layer) {
            case ENTRY -> "진입";
            case INTERNAL -> "내부";
            default -> layer.name().charAt(0) + layer.name().substring(1).toLowerCase(Locale.ROOT);
        };
    }
}
