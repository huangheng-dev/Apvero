package io.apvero.platform.release.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.release.ReleaseException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReleaseManifestValidatorTest {
    private final JsonMapper json = new JsonMapper();
    private final ReleaseManifestValidator validator = new ReleaseManifestValidator();

    @Test
    void validatesCompleteLegacyAndRagManifestsOffline() throws Exception {
        assertThatCode(() -> validator.validate(json.readTree("""
                {
                  "schemaVersion":"1.0",
                  "modelRouteVersion":"route@1",
                  "promptVersion":"prompt@2",
                  "outputSchemaVersion":"none@1",
                  "knowledgeIndexVersions":[],
                  "capabilityVersions":[],
                  "policyVersions":[],
                  "memoryPolicyVersion":"none@1",
                  "evaluationReportVersion":"not-evaluated@1",
                  "runtimeParameters":{"configurationSource":"application-draft"}
                }
                """))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(json.readTree("""
                {
                  "schemaVersion":"1.1",
                  "runtimeMode":"RAG",
                  "modelRouteVersion":"route@1",
                  "promptVersion":"prompt@2",
                  "outputSchemaVersion":"grounded-answer@1.0.0",
                  "knowledgeBindings":[{
                    "indexVersion":"support-index@1.0.0",
                    "retrievalPolicyVersion":"exact@1.0.0"
                  }],
                  "capabilityVersions":[],
                  "policyVersions":["exact@1.0.0"],
                  "memoryPolicyVersion":"none@1",
                  "evaluationReportVersion":"not-evaluated@1",
                  "runtimeParameters":{"temperature":0,"maxOutputTokens":1024}
                }
                """))).doesNotThrowAnyException();
    }

    @Test
    void rejectsIncompleteManifestWithStableCode() throws Exception {
        assertCode(
                "{\"schemaVersion\":\"1.0\"}",
                "APVERO_RELEASE_MANIFEST_INVALID");
    }

    @Test
    void rejectsLatestAndUnknownPropertiesWithStableCode() throws Exception {
        assertCode("""
                {
                  "schemaVersion":"1.0",
                  "modelRouteVersion":"route@1",
                  "promptVersion":"latest",
                  "outputSchemaVersion":"output@1",
                  "knowledgeIndexVersions":[],
                  "capabilityVersions":[],
                  "policyVersions":[],
                  "memoryPolicyVersion":"memory@1",
                  "evaluationReportVersion":"eval@1",
                  "runtimeParameters":{"configurationSource":"application-draft"}
                }
                """, "APVERO_RELEASE_MANIFEST_INVALID");
        assertCode("""
                {
                  "schemaVersion":"1.1",
                  "runtimeMode":"CHAT",
                  "modelRouteVersion":"route@1",
                  "promptVersion":"prompt@1",
                  "outputSchemaVersion":"output@1",
                  "knowledgeBindings":[],
                  "capabilityVersions":[],
                  "policyVersions":["policy@1"],
                  "memoryPolicyVersion":"memory@1",
                  "evaluationReportVersion":"eval@1",
                  "runtimeParameters":{"temperature":0,"maxOutputTokens":100},
                  "unexpected":true
                }
                """, "APVERO_RELEASE_MANIFEST_INVALID");
    }

    @Test
    void rejectsUnknownSchemaWithoutAttemptingRemoteResolution() throws Exception {
        assertCode(
                "{\"schemaVersion\":\"9.9\"}",
                "APVERO_RELEASE_MANIFEST_UNSUPPORTED");
    }

    private void assertCode(String input, String code) throws Exception {
        assertThatThrownBy(() -> validator.validate(json.readTree(input)))
                .isInstanceOf(ReleaseException.class)
                .hasMessage(code);
    }
}
