package io.apvero.platform.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import io.apvero.platform.runtime.RunRetrievalEvidence;
import io.apvero.platform.runtime.RunRetrievalExecution;
import io.apvero.platform.runtime.RunRetrievalHit;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GroundedAnswerValidatorTest {
    private final JsonMapper json = new JsonMapper();
    private final GroundedAnswerValidator validator = new GroundedAnswerValidator(json);

    @Test
    void derivesCitationIdentityFromEvidenceInRequestedMarkerOrder() throws Exception {
        var validated = validator.validate(
                providerOutput("""
                        {"schemaVersion":"1.0","status":"GROUNDED","answer":"Grounded.",
                         "citationMarkers":["[K2]","[K1]"]}
                        """),
                evidence());

        assertThat(validated.output().path("answer").stringValue())
                .isEqualTo("Grounded.");
        assertThat(validated.output().path("citations").get(0).path("marker").stringValue())
                .isEqualTo("[K2]");
        assertThat(validated.output().path("citations").get(0).path("sourceId").stringValue())
                .isEqualTo("00000000-0000-0000-0000-000000000102");
        assertThat(validated.output().path("citations").get(0).has("locator"))
                .isFalse();
        assertThat(validated.markers()).containsExactlyInAnyOrder("[K1]", "[K2]");
    }

    @Test
    void rejectsMalformedAndNonExactProviderDrafts() {
        assertThatThrownBy(() -> validator.validate(providerOutput("not-json"), evidence()))
                .isInstanceOf(GroundedOutputValidationException.class)
                .hasMessage("APVERO_GROUNDED_OUTPUT_INVALID");
        assertThatThrownBy(() -> validator.validate(
                        providerOutput("""
                                {"schemaVersion":"1.0","status":"GROUNDED","answer":"x",
                                 "citationMarkers":["[K1]"],"sourceId":"fabricated"}
                                """),
                        evidence()))
                .isInstanceOf(GroundedOutputValidationException.class)
                .hasMessage("APVERO_GROUNDED_OUTPUT_INVALID");
    }

    @Test
    void rejectsDuplicateAndUnknownMarkers() {
        assertCitationFailure("""
                {"schemaVersion":"1.0","status":"GROUNDED","answer":"x",
                 "citationMarkers":["[K1]","[K1]"]}
                """);
        assertCitationFailure("""
                {"schemaVersion":"1.0","status":"GROUNDED","answer":"x",
                 "citationMarkers":["[K999]"]}
                """);
    }

    private void assertCitationFailure(String draft) {
        assertThatThrownBy(() -> validator.validate(providerOutput(draft), evidence()))
                .isInstanceOf(GroundedOutputValidationException.class)
                .hasMessage("APVERO_CITATION_VALIDATION_FAILED");
    }

    private JsonNode providerOutput(String message) {
        return json.createObjectNode().put("message", message);
    }

    private RunRetrievalEvidence evidence() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return new RunRetrievalEvidence(
                runId,
                List.of(new RunRetrievalExecution(
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        0,
                        KnowledgeRetrievalResult.Status.MATCHES,
                        UUID.fromString("00000000-0000-0000-0000-000000000011"),
                        "knowledge-index@1.0.0",
                        UUID.fromString("00000000-0000-0000-0000-000000000012"),
                        "retrieval-policy@1.0.0",
                        "sha256:" + "a".repeat(64),
                        List.of(hit("[K1]", 1, 101), hit("[K2]", 2, 102)),
                        5,
                        1,
                        OffsetDateTime.of(2026, 7, 31, 0, 0, 0, 0, ZoneOffset.UTC))));
    }

    private RunRetrievalHit hit(String marker, int rank, int sourceSuffix) {
        return new RunRetrievalHit(
                marker,
                rank,
                new BigDecimal("0.90"),
                id(sourceSuffix),
                id(sourceSuffix + 100),
                id(sourceSuffix + 200),
                id(sourceSuffix + 300),
                "sha256:" + "b".repeat(64),
                "retained evidence",
                "Policy",
                "PDF",
                1,
                "Limits",
                2,
                3,
                4,
                false);
    }

    private UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
