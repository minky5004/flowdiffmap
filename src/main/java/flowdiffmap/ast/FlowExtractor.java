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
import flowdiffmap.graph.Edge;
import flowdiffmap.graph.Graph;
import flowdiffmap.graph.Layer;
import flowdiffmap.graph.Node;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
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
    private final DefaultPrettyPrinter bodyPrinter = new DefaultPrettyPrinter(new DefaultPrinterConfiguration()
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
        for (Path file : files) {
            Optional<CompilationUnit> cu = parse(file);
            if (cu.isEmpty()) {
                continue;
            }
            String rel = relative(file);
            for (ClassOrInterfaceDeclaration type : cu.get().findAll(ClassOrInterfaceDeclaration.class)) {
                Layer layer = layerOf(type);
                if (layer == null) {
                    continue;
                }
                String fqn = type.getFullyQualifiedName().orElseThrow();
                for (MethodDeclaration m : type.getMethods()) {
                    if (layer == Layer.CONTROLLER ? mappingOf(m).isEmpty() : m.isPrivate()) {
                        continue;
                    }
                    String endpoint = layer == Layer.CONTROLLER ? endpointOf(type, m) : null;
                    Node node = new Node(idOf(fqn, m.getNameAsString(), m.getParameters().size()),
                            fqn, m.getNameAsString(), layer, endpoint, hash(m), rel);
                    nodes.put(node.id(), node);
                    for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                        calleeFqn(call)
                                .filter(callee -> !callee.equals(fqn))
                                .ifPresent(callee -> edges.add(new Edge(node.id(),
                                        idOf(callee, call.getNameAsString(), call.getArguments().size()), rel)));
                    }
                }
            }
        }
        return new Graph(nodes, edges);
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
            // @RequestMapping(method = RequestMethod.POST)
            verb = memberOf(n, "method")
                    .map(v -> v.toString().substring(v.toString().lastIndexOf('.') + 1))
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

    private String hash(MethodDeclaration m) {
        String body = m.getBody().map(bodyPrinter::print).orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String relative(Path file) {
        return srcRoot.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String idOf(String fqn, String method, int arity) {
        return fqn + "#" + method + "/" + arity;
    }
}
