package io.apvero.platform.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class P23fCompatibilityContractTest {
    @Test
    void publishesManifestElevenWithoutWeakeningTheLegacyContract()
            throws Exception {
        Map<String, Object> manifestEleven =
                new Yaml().load(Files.readString(repositoryFile(
                        "contracts/schemas/release-bundle-manifest.v1.1.schema.json")));
        Map<String, Object> legacy =
                new Yaml().load(Files.readString(repositoryFile(
                        "contracts/schemas/release-bundle-manifest.schema.json")));

        assertThat(manifestEleven.get("x-apvero-contract-status"))
                .isEqualTo("baseline");
        assertThat(manifestEleven.get("additionalProperties")).isEqualTo(false);
        assertThat(legacy.get("x-apvero-contract-status"))
                .isEqualTo("legacy-live");
        assertThat(legacy.get("additionalProperties")).isEqualTo(false);
    }

    private static Path repositoryFile(String relative) {
        return Path.of("..", "..", relative).toAbsolutePath().normalize();
    }
}
