package flowdiffmap.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration.ConfigOption;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.TypeSolverBuilder;
import flowdiffmap.graph.Component;
import flowdiffmap.graph.Edge;
import flowdiffmap.graph.Graph;
import flowdiffmap.graph.Layer;
import flowdiffmap.graph.Node;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Spring 컴포넌트(Controller · Service · Repository)와 그 밖의 클래스(진입점 · 내부)의 메서드를 노드로,
 * 소스 안 다른 클래스로의 호출을 엣지로 뽑는다 — 그림에 넣을 클래스는 {@code GraphStore.load} 가 고른다.
 *
 * <p>호출 대상은 메서드가 아니라 scope 의 타입만 resolve 한다 — 대상 리포의 의존 jar 가 타입 솔버에 없어서
 * {@code orderRepository.save()} 처럼 {@code JpaRepository} 에서 상속한 메서드는 메서드 resolve 가 항상 실패한다.
 */
public class FlowExtractor {

    private static final Map<String, String> VERBS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH",
            "RequestMapping", "ANY");

    private static final Set<String> JDK_CALLBACKS = Set.of(
            "java.lang.Runnable", "java.lang.Thread", "java.util.TimerTask", "java.util.concurrent.Callable");

    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString");

    private final Path srcRoot;
    private final JavaParser parser;
    private final Set<String> unparsed = new HashSet<>();
    private final DefaultPrettyPrinter declarationPrinter = new DefaultPrettyPrinter(new DefaultPrinterConfiguration()
            .removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_COMMENTS))
            .removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_JAVADOC)));

    public FlowExtractor(Path srcRoot) {
        this.srcRoot = srcRoot.toAbsolutePath().normalize();
        var typeSolver = new TypeSolverBuilder().withCurrentJRE().withSourceCode(this.srcRoot).build();
        this.parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.JAVA_25)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver)));
    }

    /** 파싱에 실패한 파일은 경고만 남기고 건너뛴다 — 목록은 {@link #unparsed()}. */
    public Graph extract(Collection<Path> files) {
        unparsed.clear();
        Map<String, Node> nodes = new HashMap<>();
        Set<Edge> edges = new HashSet<>();
        Set<Component> components = new HashSet<>();
        for (Path file : files) {
            Path abs = file.toAbsolutePath().normalize();
            if (!abs.startsWith(srcRoot)) {
                System.err.println("[flowdiffmap] 소스 루트 밖 · 건너뜀: " + file);
                continue;
            }
            String rel = srcRoot.relativize(abs).toString().replace('\\', '/');
            Optional<CompilationUnit> cu = parse(abs);
            if (cu.isEmpty()) {
                unparsed.add(rel);
                continue;
            }
            for (ClassOrInterfaceDeclaration type : cu.get().findAll(ClassOrInterfaceDeclaration.class)) {
                // 로컬 클래스와 그 안 중첩 클래스는 FQN 이 없다 — 감싸는 메서드 본문의 일부로 엔트리 해시 · 헬퍼에 이미 들어간다
                Optional<String> named = type.getFullyQualifiedName();
                Layer layer = named.isEmpty() ? null : layerOf(type);
                if (layer == null) {
                    continue;
                }
                String fqn = named.get();
                components.add(new Component(fqn, layer, rel));
                for (MethodDeclaration m : type.getMethods()) {
                    if (!isNode(layer, m)) {
                        continue;
                    }
                    List<MethodDeclaration> reach = withHelpers(type, layer, m);
                    String endpoint = layer == Layer.CONTROLLER ? endpointOf(type, m) : null;
                    Node node = new Node(idOf(fqn, m.getNameAsString(), m.getParameters().size()),
                            fqn, m.getNameAsString(), nodeLayer(layer, m), endpoint, hash(reach), rel);
                    nodes.put(node.id(), node);
                    for (MethodDeclaration r : reach) {
                        for (MethodCallExpr call : r.findAll(MethodCallExpr.class)) {
                            calleeFqn(call)
                                    .filter(callee -> !callee.equals(fqn))
                                    .ifPresent(callee -> edges.add(new Edge(node.id(),
                                            idOf(callee, call.getNameAsString(), call.getArguments().size()), rel)));
                        }
                    }
                }
            }
        }
        return new Graph(nodes, edges, components);
    }

    /**
     * 직전 {@link #extract} 에서 읽지 못한 파일 · {@link Node#file()} 과 같은 형식 — 문법 오류 중인 파일의 노드가
     * 전부 삭제로 보이지 않게, 호출자가 부모 스냅샷 행을 그대로 두는 데 쓴다.
     */
    public Set<String> unparsed() {
        return Set.copyOf(unparsed);
    }

    /** 헬퍼로 흡수되지 않는 메서드 — 컨트롤러는 핸들러만 · 진입점 클래스는 main 과 프레임워크 콜백(@Override)만 · 나머지는 private 이 아닌 메서드. */
    private static boolean isEntry(Layer layer, MethodDeclaration m) {
        return switch (layer) {
            case CONTROLLER -> mappingOf(m).isPresent();
            case ENTRY -> isMain(m) || isCallback(m);
            default -> !m.isPrivate();
        };
    }

    /**
     * 노드가 되는 메서드 — 진입점 클래스는 콜백 밖의 private 아닌 메서드도(다른 클래스가 부르는 리스너 유틸).
     * 그 메서드는 같은 클래스 콜백의 헬퍼로도 흡수된다 — 같은 클래스 안 호출은 엣지가 아니라서, 흡수하지 않으면
     * 콜백에서 그 메서드를 거쳐 나가는 호출이 진입점 도달 판정에서 끊긴다.
     */
    private static boolean isNode(Layer layer, MethodDeclaration m) {
        return isEntry(layer, m) || layer == Layer.ENTRY && !m.isPrivate();
    }

    /**
     * 진입점 클래스에서 진입점은 main 과 프레임워크 콜백(@Override)뿐 — 다른 클래스가 부르는 리스너의 public 유틸은
     * 내부 노드라야 진입 칸에 서지 않고 본문 변경도 제 노드에 칠해진다.
     */
    private static Layer nodeLayer(Layer layer, MethodDeclaration m) {
        return layer == Layer.ENTRY && !isEntry(layer, m) ? Layer.INTERNAL : layer;
    }

    /**
     * 엔트리 메서드와, 거기서 같은 클래스 안으로 부르는 헬퍼(엔트리가 아닌 메서드)들.
     * 헬퍼를 거친 레이어 간 호출과 헬퍼 본문의 변경을 엔트리 노드 몫으로 돌린다.
     */
    private static List<MethodDeclaration> withHelpers(ClassOrInterfaceDeclaration type, Layer layer, MethodDeclaration entry) {
        List<MethodDeclaration> reach = new ArrayList<>(List.of(entry));
        for (int i = 0; i < reach.size(); i++) {
            for (MethodCallExpr call : reach.get(i).findAll(MethodCallExpr.class)) {
                if (call.getScope().isPresent() && !call.getScope().get().isThisExpr()) {
                    continue;
                }
                for (MethodDeclaration helper : type.getMethodsByName(call.getNameAsString())) {
                    if (helper.getParameters().size() == call.getArguments().size()
                            && !isEntry(layer, helper)
                            && reach.stream().noneMatch(seen -> seen == helper)) {
                        reach.add(helper);
                    }
                }
            }
        }
        return reach;
    }

    private Optional<CompilationUnit> parse(Path file) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (result.isSuccessful()) {
                return result.getResult();
            }
            System.err.println("[flowdiffmap] 파싱 실패 · 건너뜀: " + file + " — " + result.getProblems().getFirst().getMessage());
        } catch (IOException e) {
            System.err.println("[flowdiffmap] 읽기 실패 · 건너뜀: " + file + " — " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * scope 의 타입이 소스 안의 클래스일 때 그 FQN — 컴포넌트가 아니어도 남긴다.
     * 증분 저장에서 callee 파일만 바뀌어 컴포넌트가 되면(어노테이션 추가) 안 바뀐 호출자 엣지가 없어서는 안 되므로,
     * 컴포넌트 여부는 스냅샷을 읽을 때 거른다.
     */
    private static Optional<String> calleeFqn(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return Optional.empty();
        }
        try {
            ResolvedType type = call.getScope().get().calculateResolvedType();
            if (!type.isReferenceType()) {
                return Optional.empty();
            }
            return type.asReferenceType().getTypeDeclaration()
                    .flatMap(declaration -> declaration.toAst())
                    .flatMap(ast -> ast instanceof ClassOrInterfaceDeclaration c
                            ? c.getFullyQualifiedName()
                            : Optional.empty());
        } catch (RuntimeException e) {
            // 외부 jar 타입 · 상속 메서드 체인(findById(id).orElseThrow()) — 레이어 간 호출이 아니라서 버린다
            return Optional.empty();
        }
    }

    static Layer layerOf(ClassOrInterfaceDeclaration type) {
        for (AnnotationExpr a : type.getAnnotations()) {
            switch (a.getName().getIdentifier()) {
                case "RestController", "Controller" -> {
                    return Layer.CONTROLLER;
                }
                case "Service" -> {
                    return Layer.SERVICE;
                }
                case "Repository" -> {
                    return Layer.REPOSITORY;
                }
                default -> {
                }
            }
        }
        // Spring Data 리포지토리는 어노테이션 없이 JpaRepository 등을 확장만 하는 경우가 대부분
        if (type.isInterface()) {
            boolean springData = type.getExtendedTypes().stream()
                    .anyMatch(t -> t.getNameAsString().endsWith("Repository"));
            return springData ? Layer.REPOSITORY : null;
        }
        // Spring 이 아닌 클래스 — 흐름에 넣을지는 GraphStore.load 가 진입점 도달 여부로 정한다
        return isEntryClass(type) ? Layer.ENTRY : Layer.INTERNAL;
    }

    /**
     * {@code main} 이 있거나, 프레임워크 타입(ListenerAdapter 등)을 상속 · 구현하면서 콜백을 재정의한 클래스.
     * 소스 안 인터페이스 구현 · {@code Comparable} 같은 JDK 비콜백 타입 구현 · Object 메서드만 재정의한 클래스는 진입점이 아니다.
     */
    private static boolean isEntryClass(ClassOrInterfaceDeclaration type) {
        if (type.getMethods().stream().anyMatch(FlowExtractor::isMain)) {
            return true;
        }
        // 재정의 확인이 먼저 — 상위 타입 resolve 는 대부분의 클래스에서 건너뛴다
        return type.getMethods().stream().anyMatch(FlowExtractor::isCallback) && extendsFramework(type, new HashSet<>());
    }

    /** {@code main(String[])} · Java 25 인스턴스 · 인자 없는 {@code main()} — static 여부는 묻지 않는다. */
    private static boolean isMain(MethodDeclaration m) {
        if (!m.getNameAsString().equals("main") || !m.getType().isVoidType()) {
            return false;
        }
        if (m.getParameters().isEmpty()) {
            return true;
        }
        if (m.getParameters().size() != 1) {
            return false;
        }
        var p = m.getParameter(0);
        return (p.getType().asString().replace("java.lang.", "") + (p.isVarArgs() ? "[]" : "")).equals("String[]");
    }

    /** equals · hashCode · toString 재정의는 프레임워크 콜백이 아니다. */
    private static boolean isCallback(MethodDeclaration m) {
        return m.isAnnotationPresent(Override.class) && !OBJECT_METHODS.contains(m.getNameAsString());
    }

    /** 상위 타입 중 프레임워크 타입이 있는가 — 소스 안 상위 타입은 거슬러 올라간다(BaseCommand extends ListenerAdapter). */
    private static boolean extendsFramework(ClassOrInterfaceDeclaration type, Set<String> seen) {
        if (!seen.add(type.getFullyQualifiedName().orElse(type.getNameAsString()))) {
            return false;
        }
        return Stream.concat(type.getExtendedTypes().stream(), type.getImplementedTypes().stream())
                .anyMatch(t -> isFramework(t, seen));
    }

    /**
     * 소스 밖 타입이면서 JDK 가 아닌 것 · JDK 중엔 실행 콜백만 — resolve 실패(의존 jar 가 타입 솔버에 없음)도 프레임워크로 본다.
     */
    private static boolean isFramework(ClassOrInterfaceType t, Set<String> seen) {
        try {
            var declaration = t.resolve().asReferenceType().getTypeDeclaration().orElseThrow();
            var ast = declaration.toAst();
            if (ast.isEmpty()) {
                String name = declaration.getQualifiedName();
                return !name.startsWith("java.") || JDK_CALLBACKS.contains(name);
            }
            return ast.get() instanceof ClassOrInterfaceDeclaration c && extendsFramework(c, seen);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static Optional<AnnotationExpr> mappingOf(NodeWithAnnotations<?> target) {
        return target.getAnnotations().stream()
                .filter(a -> VERBS.containsKey(a.getName().getIdentifier()))
                .findFirst();
    }

    private static String endpointOf(ClassOrInterfaceDeclaration type, MethodDeclaration m) {
        AnnotationExpr mapping = mappingOf(m).orElseThrow();
        String prefix = mappingOf(type).map(FlowExtractor::pathOf).orElse("");
        String path = (prefix + "/" + pathOf(mapping)).replaceAll("/+", "/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return verbOf(mapping) + " " + path;
    }

    private static String verbOf(AnnotationExpr mapping) {
        String verb = VERBS.get(mapping.getName().getIdentifier());
        if (mapping instanceof NormalAnnotationExpr n) {
            // @RequestMapping(method = RequestMethod.POST) · method = {GET, POST} 는 GET,POST
            verb = memberOf(n, "method")
                    .map(v -> v instanceof ArrayInitializerExpr array ? array.getValues() : List.of(v))
                    .map(values -> String.join(",", values.stream()
                            .map(v -> v.toString().substring(v.toString().lastIndexOf('.') + 1))
                            .toList()))
                    .orElse(verb);
        }
        return verb;
    }

    private static String pathOf(AnnotationExpr a) {
        Expression value = switch (a) {
            case SingleMemberAnnotationExpr s -> s.getMemberValue();
            case NormalAnnotationExpr n -> memberOf(n, "value").or(() -> memberOf(n, "path")).orElse(null);
            default -> null;
        };
        if (value instanceof ArrayInitializerExpr array) {
            value = array.getValues().getFirst().orElse(null);
        }
        return value instanceof StringLiteralExpr s ? s.getValue() : "";
    }

    private static Optional<Expression> memberOf(NormalAnnotationExpr a, String name) {
        return a.getPairs().stream()
                .filter(p -> p.getNameAsString().equals(name))
                .map(MemberValuePair::getValue)
                .findFirst();
    }

    /** 주석을 뺀 선언 전체(어노테이션 · 시그니처 · 본문) — 본문 없는 쿼리 메서드의 {@code @Query} 변경도 잡는다. */
    private String hash(List<MethodDeclaration> reach) {
        StringBuilder printed = new StringBuilder();
        reach.forEach(m -> printed.append(declarationPrinter.print(m)).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(printed.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String idOf(String fqn, String method, int arity) {
        return fqn + "#" + method + "/" + arity;
    }
}
