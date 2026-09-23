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

/**
 * Spring 컴포넌트(Controller · Service · Repository)의 메서드를 노드로, 레이어 간 호출을 엣지로 뽑는다.
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

    private final Path srcRoot;
    private final JavaParser parser;
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

    /** 파싱에 실패한 파일은 경고만 남기고 건너뛴다. */
    public Graph extract(Collection<Path> files) {
        Map<String, Node> nodes = new HashMap<>();
        Set<Edge> edges = new HashSet<>();
        Set<Component> components = new HashSet<>();
        for (Path file : files) {
            Path abs = file.toAbsolutePath().normalize();
            if (!abs.startsWith(srcRoot)) {
                System.err.println("[flowdiffmap] 소스 루트 밖 · 건너뜀: " + file);
                continue;
            }
            Optional<CompilationUnit> cu = parse(abs);
            if (cu.isEmpty()) {
                continue;
            }
            String rel = srcRoot.relativize(abs).toString().replace('\\', '/');
            for (ClassOrInterfaceDeclaration type : cu.get().findAll(ClassOrInterfaceDeclaration.class)) {
                Layer layer = layerOf(type);
                if (layer == null) {
                    continue;
                }
                String fqn = type.getFullyQualifiedName().orElseThrow();
                components.add(new Component(fqn, layer, rel));
                for (MethodDeclaration m : type.getMethods()) {
                    if (!isEntry(layer, m)) {
                        continue;
                    }
                    List<MethodDeclaration> reach = withHelpers(type, layer, m);
                    String endpoint = layer == Layer.CONTROLLER ? endpointOf(type, m) : null;
                    Node node = new Node(idOf(fqn, m.getNameAsString(), m.getParameters().size()),
                            fqn, m.getNameAsString(), layer, endpoint, hash(reach), rel);
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

    /** 컨트롤러는 핸들러 메서드만 · 나머지는 private 이 아닌 메서드. */
    private static boolean isEntry(Layer layer, MethodDeclaration m) {
        return layer == Layer.CONTROLLER ? mappingOf(m).isPresent() : !m.isPrivate();
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

    /** scope 의 타입이 소스 안의 컴포넌트일 때만 그 FQN. */
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
                    .flatMap(ast -> ast instanceof ClassOrInterfaceDeclaration c && layerOf(c) != null
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
        boolean springData = type.isInterface() && type.getExtendedTypes().stream()
                .anyMatch(t -> t.getNameAsString().endsWith("Repository"));
        return springData ? Layer.REPOSITORY : null;
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
