package io.apvero.platform.release.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class P23bReleaseContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void standardReleaseRequestCannotSupplyOrOverrideManifestPins() throws Exception {
        assertThat(ReleaseController.CreateReleaseRequest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("version");

        Map<String, Object> document = new Yaml().load(Files.readString(
                repositoryFile("contracts/openapi/platform-api.yaml")));
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        Map<String, Object> releasePath = (Map<String, Object>) paths.get(
                "/api/v1/applications/{applicationId}/releases");
        Map<String, Object> post = (Map<String, Object>) releasePath.get("post");
        Map<String, Object> requestBody = (Map<String, Object>) post.get("requestBody");
        Map<String, Object> content = (Map<String, Object>) requestBody.get("content");
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        Map<String, Object> schema = (Map<String, Object>) json.get("schema");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        assertThat((java.util.List<String>) schema.get("required"))
                .containsExactly("version");
        assertThat(properties.keySet()).containsExactly("version");
    }

    private static Path repositoryFile(String relative) {
        return Path.of("..", "..", relative).toAbsolutePath().normalize();
    }
}
