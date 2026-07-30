package io.apvero.platform.knowledge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

class KnowledgeOpenApiConformanceTest {
    private static final Set<String> P21_PATH_PREFIXES = Set.of(
            "/api/v1/knowledge-bases",
            "/api/v1/knowledge-sources",
            "/api/v1/knowledge-source-revisions",
            "/api/v1/knowledge-ingestion-jobs");
    private static final Set<Operation> IMPLEMENTED_KNOWLEDGE_OPERATIONS = Set.of(
            new Operation("get", "/api/v1/knowledge-indexes/{indexId}/builds"),
            new Operation("post", "/api/v1/knowledge-indexes/{indexId}/builds"),
            new Operation("get", "/api/v1/knowledge-index-builds/{buildId}"),
            new Operation("post", "/api/v1/knowledge-index-builds/{buildId}/retry"),
            new Operation("post", "/api/v1/knowledge-index-builds/{buildId}/cancel"),
            new Operation("get", "/api/v1/retrieval-policy-versions"),
            new Operation("post", "/api/v1/retrieval-policy-versions"),
            new Operation("post", "/api/v1/knowledge-retrieval-tests"));

    @Test
    void everyImplementedKnowledgeOperationMatchesTheCommittedOpenApiMethodAndPath() throws Exception {
        assertThat(controllerOperations()).isEqualTo(contractOperations());
    }

    private static Set<Operation> controllerOperations() {
        Set<Operation> operations = new LinkedHashSet<>();
        for (Class<?> controller : Set.of(
                KnowledgeController.class,
                KnowledgeIndexBuildController.class,
                RetrievalPolicyVersionController.class,
                KnowledgeRetrievalController.class)) {
            String basePath = controller.getAnnotation(RequestMapping.class).value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                add(operations, basePath, method, GetMapping.class, "get");
                add(operations, basePath, method, PostMapping.class, "post");
                add(operations, basePath, method, DeleteMapping.class, "delete");
            }
        }
        return operations;
    }

    private static <A extends Annotation> void add(
            Set<Operation> operations,
            String basePath,
            Method method,
            Class<A> annotationType,
            String httpMethod) {
        A annotation = method.getAnnotation(annotationType);
        if (annotation == null) {
            return;
        }
        String[] paths = switch (annotation) {
            case GetMapping mapping -> firstNonEmpty(mapping.value(), mapping.path());
            case PostMapping mapping -> firstNonEmpty(mapping.value(), mapping.path());
            case DeleteMapping mapping -> firstNonEmpty(mapping.value(), mapping.path());
            default -> throw new IllegalArgumentException("Unsupported mapping annotation");
        };
        assertThat(paths).as("mapping path for %s", method.getName()).hasSize(1);
        operations.add(new Operation(httpMethod, basePath + paths[0]));
    }

    private static String[] firstNonEmpty(String[] value, String[] path) {
        if (value.length > 0) {
            return value;
        }
        return path.length > 0 ? path : new String[] {""};
    }

    @SuppressWarnings("unchecked")
    private static Set<Operation> contractOperations() throws Exception {
        Path contract = Path.of("..", "..", "contracts", "openapi", "platform-api.yaml")
                .toAbsolutePath().normalize();
        Map<String, Object> document = new Yaml().load(Files.readString(contract));
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        Set<Operation> operations = new LinkedHashSet<>();
        paths.forEach((path, value) -> {
            Map<String, Object> pathItem = (Map<String, Object>) value;
            for (String method : Set.of("get", "post", "delete")) {
                if (!pathItem.containsKey(method)) {
                    continue;
                }
                Operation candidate = new Operation(method, path);
                Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
                if (P21_PATH_PREFIXES.stream().anyMatch(path::startsWith)) {
                    assertThat(operation.get("x-apvero-implementation-status"))
                            .as("%s %s remains contract-only until P2 acceptance", method, path)
                            .isEqualTo("contract-only");
                    operations.add(candidate);
                } else if (IMPLEMENTED_KNOWLEDGE_OPERATIONS.contains(candidate)) {
                    assertThat(operation)
                            .as("%s %s is implemented", method, path)
                            .doesNotContainKey("x-apvero-implementation-status");
                    operations.add(candidate);
                }
            }
        });
        return operations;
    }

    private record Operation(String method, String path) {}
}
