package io.apvero.platform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeRetrievalBoundaryTest {
    @Test
    void resultCopiesHitsAndEnforcesStatusShape() {
        ArrayList<KnowledgeRetrievalHit> mutable = new ArrayList<>(List.of(hit()));
        KnowledgeRetrievalResult result = new KnowledgeRetrievalResult(
                KnowledgeRetrievalResult.Status.MATCHES,
                UUID.randomUUID(),
                UUID.randomUUID(),
                digest('a'),
                mutable,
                12);
        mutable.clear();

        assertThat(result.hits()).hasSize(1);
        assertThatThrownBy(() -> result.hits().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new KnowledgeRetrievalResult(
                        KnowledgeRetrievalResult.Status.NO_EVIDENCE,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        digest('b'),
                        List.of(hit()),
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_RETRIEVAL_RESULT_INVALID");
        assertThatThrownBy(() -> new KnowledgeRetrievalResult(
                        KnowledgeRetrievalResult.Status.MATCHES,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        digest('c'),
                        List.of(),
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_RETRIEVAL_RESULT_INVALID");
    }

    @Test
    void hitEnforcesContractBounds() {
        assertThatThrownBy(() -> copyHit(0, BigDecimal.ONE, digest('a'), "content"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyHit(1, new BigDecimal("1.01"), digest('a'), "content"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyHit(1, BigDecimal.ONE, "not-a-digest", "content"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyHit(1, BigDecimal.ONE, digest('a'), "x".repeat(20_001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static KnowledgeRetrievalHit copyHit(
            int rank, BigDecimal score, String digest, String content) {
        KnowledgeRetrievalHit source = hit();
        return new KnowledgeRetrievalHit(
                rank,
                score,
                source.sourceId(),
                source.sourceRevisionId(),
                source.documentId(),
                source.chunkId(),
                digest,
                content,
                source.sourceTitle(),
                source.sourceType(),
                source.page(),
                source.heading(),
                source.paragraph(),
                source.lineStart(),
                source.lineEnd());
    }

    private static KnowledgeRetrievalHit hit() {
        return new KnowledgeRetrievalHit(
                1,
                BigDecimal.ONE,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                digest('a'),
                "evidence",
                "Source",
                KnowledgeSource.Type.TEXT,
                1,
                "Heading",
                1,
                1,
                1);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
