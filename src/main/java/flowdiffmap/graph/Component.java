package flowdiffmap.graph;

/**
 * 레이어가 붙은 클래스 하나 — 선언 메서드가 없어도(상속만 하는 Spring Data 리포지토리) 남는다.
 * 엣지가 가리키는 클래스가 여전히 컴포넌트인지 · 암묵 노드를 어느 레이어에 둘지 여기서 본다.
 */
public record Component(String fqn, Layer layer, String file) {
}
