package io.apvero.platform.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordRetrievalEvidenceCommandTest {
    private final KnowledgeRetrievalResult noEvidence = new KnowledgeRetrievalResult(
            KnowledgeRetrievalResult.Status.NO_EVIDENCE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "sha256:" + "a".repeat(64),
            List.of(),
            0);

    @Test
    void disclosesContentOnlyForTheExplicitUnmaskedRetentionDecision() {
        assertThat(command(0, "index@1.0.0", 1, true, false).discloseContent()).isTrue();
        assertThat(command(0, "index@1.0.0", 1, false, false).discloseContent()).isFalse();
        assertThat(command(0, "index@1.0.0", 1, true, true).discloseContent()).isFalse();
    }

    @Test
    void rejectsOutOfRangeSequenceUnversionedReferencesAndMissingRetentionProvenance() {
        assertThatThrownBy(() -> command(16, "index@1.0.0", 1, true, false))
                .isInstanceOf(RunEvidenceException.class);
        assertThatThrownBy(() -> command(0, "index@latest", 1, true, false))
                .isInstanceOf(RunEvidenceException.class);
        assertThatThrownBy(() -> command(0, "index@1.0.0", 0, true, false))
                .isInstanceOf(RunEvidenceException.class);
    }

    private RecordRetrievalEvidenceCommand command(
            int sequence,
            String indexReference,
            long retentionVersion,
            boolean retain,
            boolean mask) {
        return new RecordRetrievalEvidenceCommand(
                sequence,
                indexReference,
                "policy@1.0.0",
                retentionVersion,
                retain,
                mask,
                noEvidence);
    }
}
