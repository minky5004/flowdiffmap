package flowdiffmap.graph;

/**
 * 레이어 간 호출 하나.
 *
 * @param file 호출하는 쪽 파일 — 그 파일이 바뀌면 엣지도 다시 만든다
 */
public record Edge(String from, String to, String file) {
}
