package flowdiffmap.graph;

/**
 * 컴포넌트 클래스의 메서드 하나.
 *
 * @param id       {@code fqn#method/arity} — 오버로드는 인자 수로만 구분
 * @param endpoint 컨트롤러 핸들러만 ({@code GET /orders/{id}}) · 나머지는 null
 * @param bodyHash 주석을 뺀 본문의 SHA-256 — 달라지면 "변경"
 * @param file     소스 루트 기준 상대 경로 · {@code /} 구분
 */
public record Node(String id, String fqn, String method, Layer layer, String endpoint, String bodyHash, String file) {
}
