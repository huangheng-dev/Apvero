package io.apvero.platform.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class P23eCitationContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void publishesGroundedAnswerCitationAndCitationReadBaselines() throws Exception {
        Map<String, Object> api = new Yaml().load(Files.readString(repositoryFile(
                "contracts/openapi/platform-api.yaml")));
        Map<String, Object> paths = (Map<String, Object>) api.get("paths");
        Map<String, Object> citationPath =
                (Map<String, Object>) paths.get("/api/v1/runs/{runId}/citations");
        assertThat(((Map<String, Object>) citationPath.get("get"))
                        .get("x-apvero-implementation-status"))
                .isEqualTo("baseline");

        assertSchemaBaseline("contracts/schemas/grounded-answer.v1.schema.json");
        assertSchemaBaseline("contracts/schemas/citation.v1.schema.json");
    }

    private void assertSchemaBaseline(String relative) throws Exception {
        Map<String, Object> schema =
                new Yaml().load(Files.readString(repositoryFile(relative)));
        assertThat(schema.get("x-apvero-contract-status")).isEqualTo("baseline");
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
    }

    private static Path repositoryFile(String relative) {
        return Path.of("..", "..", relative).toAbsolutePath().normalize();
    }
}
