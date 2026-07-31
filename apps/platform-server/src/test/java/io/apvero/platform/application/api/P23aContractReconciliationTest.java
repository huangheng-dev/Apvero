package io.apvero.platform.application.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

class P23aContractReconciliationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void manifestUsesImplementedIntegerIdentitiesAndSemanticKnowledgeIdentities()
            throws Exception {
        var schema = json.readTree(Files.readString(repositoryFile(
                "contracts/schemas/release-bundle-manifest.v1.1.schema.json")));
        assertThat(schema.path("properties").path("modelRouteVersion").path("$ref").stringValue())
                .isEqualTo("#/$defs/integerVersionReference");
        assertThat(schema.path("properties").path("promptVersion").path("$ref").stringValue())
                .isEqualTo("#/$defs/integerVersionReference");
        assertThat(schema.path("$defs").path("knowledgeBinding").path("properties")
                        .path("indexVersion").path("$ref").stringValue())
                .isEqualTo("#/$defs/semanticVersionReference");

        Pattern integer = pattern(schema, "integerVersionReference");
        Pattern exact = pattern(schema, "exactArtifactReference");
        Pattern semantic = pattern(schema, "semanticVersionReference");
        assertThat(integer.matcher("support-route@3")).matches();
        assertThat(integer.matcher("support-route@1.0.0").matches()).isFalse();
        assertThat(exact.matcher("none@1")).matches();
        assertThat(exact.matcher("policy@1.0.0")).matches();
        assertThat(semantic.matcher("support-index@1.0.0")).matches();
        assertThat(semantic.matcher("support-index@3").matches()).isFalse();
        assertThat(integer.matcher("support-route@latest").matches()).isFalse();
        assertThat(semantic.matcher("support-index@latest").matches()).isFalse();
    }

    @Test
    void ragManifestExampleUsesEveryFieldSpecificVersionFormat() throws Exception {
        var schema = json.readTree(Files.readString(repositoryFile(
                "contracts/schemas/release-bundle-manifest.v1.1.schema.json")));
        var example = json.readTree(Files.readString(repositoryFile(
                "contracts/examples/release-bundle-manifest.v1.1.rag.json")));

        assertThat(pattern(schema, "integerVersionReference")
                        .matcher(example.path("modelRouteVersion").stringValue()))
                .matches();
        assertThat(pattern(schema, "integerVersionReference")
                        .matcher(example.path("promptVersion").stringValue()))
                .matches();
        assertThat(pattern(schema, "exactArtifactReference")
                        .matcher(example.path("outputSchemaVersion").stringValue()))
                .matches();
        assertThat(pattern(schema, "semanticVersionReference")
                        .matcher(example.path("knowledgeBindings").get(0)
                                .path("indexVersion").stringValue()))
                .matches();
        assertThat(pattern(schema, "semanticVersionReference")
                        .matcher(example.path("knowledgeBindings").get(0)
                                .path("retrievalPolicyVersion").stringValue()))
                .matches();
    }

    @Test
    @SuppressWarnings("unchecked")
    void bindingContractContainsOnlyOpaqueIdsOrderAndConcurrencyMetadata()
            throws Exception {
        Map<String, Object> document = new Yaml().load(Files.readString(
                repositoryFile("contracts/openapi/platform-api.yaml")));
        Map<String, Object> components = (Map<String, Object>) document.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> binding =
                (Map<String, Object>) schemas.get("ApplicationKnowledgeBinding");
        Map<String, Object> properties =
                (Map<String, Object>) binding.get("properties");
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "indexVersionId", "retrievalPolicyVersionId", "bindingOrder");
        assertThat((Set<String>) Set.copyOf((java.util.List<String>) binding.get("required")))
                .containsExactlyInAnyOrder(
                        "indexVersionId", "retrievalPolicyVersionId", "bindingOrder");

        Map<String, Object> request =
                (Map<String, Object>) schemas.get("ReplaceApplicationKnowledgeBindingsRequest");
        assertThat((java.util.List<String>) request.get("required"))
                .contains("expectedApplicationVersion", "bindings");

        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        Map<String, Object> path = (Map<String, Object>) paths.get(
                "/api/v1/applications/{applicationId}/draft/knowledge-bindings");
        assertThat((Map<String, Object>) path.get("get"))
                .doesNotContainKey("x-apvero-implementation-status");
        assertThat((Map<String, Object>) path.get("put"))
                .doesNotContainKey("x-apvero-implementation-status");
    }

    private static Pattern pattern(tools.jackson.databind.JsonNode schema, String name) {
        return Pattern.compile(schema.path("$defs").path(name).path("pattern").stringValue());
    }

    private static Path repositoryFile(String relative) {
        return Path.of("..", "..", relative).toAbsolutePath().normalize();
    }
}
