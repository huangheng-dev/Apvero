package io.apvero.platform.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class P23cRunEvidenceContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void exposesDedicatedMarkerBearingRunEvidence()
            throws Exception {
        Map<String, Object> document = new Yaml().load(Files.readString(repositoryFile(
                "contracts/openapi/platform-api.yaml")));
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        Map<String, Object> retrievalPath =
                (Map<String, Object>) paths.get("/api/v1/runs/{runId}/retrieval");
        Map<String, Object> retrievalGet = (Map<String, Object>) retrievalPath.get("get");
        assertThat(retrievalGet.get("x-apvero-implementation-status")).isEqualTo("baseline");
        Map<String, Object> components = (Map<String, Object>) document.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> hit = (Map<String, Object>) schemas.get("RunRetrievalHit");
        assertThat((List<String>) hit.get("required"))
                .contains("marker", "contentDigest", "citationValidated");
        Map<String, Object> execution =
                (Map<String, Object>) schemas.get("RunRetrievalExecution");
        assertThat((List<String>) execution.get("required"))
                .contains(
                        "indexVersionReference",
                        "retrievalPolicyVersionReference",
                        "retentionDecisionVersion");
        assertThat(execution).doesNotContainKey("allOf");
    }

    private static Path repositoryFile(String relative) {
        return Path.of("..", "..", relative).toAbsolutePath().normalize();
    }
}
