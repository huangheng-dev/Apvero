package io.apvero.platform.release.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class ReleaseManifestValidatorTest {
    private final JsonMapper json = new JsonMapper();
    private final ReleaseManifestValidator validator = new ReleaseManifestValidator();

    @Test
    void rejectsIncompleteManifest() throws Exception {
        var manifest = json.readTree("{\"schemaVersion\":\"1.0\"}");

        assertThatThrownBy(() -> validator.validate(manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelRouteVersion");
    }

    @Test
    void rejectsLatestReferences() throws Exception {
        var manifest = json.readTree("""
                {
                  "schemaVersion":"1.0",
                  "modelRouteVersion":"route@1.0.0",
                  "promptVersion":"latest",
                  "outputSchemaVersion":"output@1.0.0",
                  "knowledgeIndexVersions":[],
                  "capabilityVersions":[],
                  "policyVersions":["policy@1.0.0"],
                  "memoryPolicyVersion":"memory@1.0.0",
                  "evaluationReportVersion":"eval@1.0.0",
                  "runtimeParameters":{}
                }
                """);

        assertThatThrownBy(() -> validator.validate(manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latest");
    }
}
