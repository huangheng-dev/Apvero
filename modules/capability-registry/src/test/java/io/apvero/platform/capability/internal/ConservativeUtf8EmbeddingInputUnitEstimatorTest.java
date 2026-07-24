package io.apvero.platform.capability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ConservativeUtf8EmbeddingInputUnitEstimatorTest {
    private final ConservativeUtf8EmbeddingInputUnitEstimator estimator =
            new ConservativeUtf8EmbeddingInputUnitEstimator();

    @Test
    void freezesTheBilingualAndAdversarialCorpusDecision() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/p2-2a-embedding-corpus.json")) {
            assertThat(input).isNotNull();
            List<CorpusCase> corpus = new ObjectMapper().readerForListOf(CorpusCase.class).readValue(input);

            assertThat(corpus).extracting(CorpusCase::category)
                    .contains("en", "zh-CN", "mixed", "long-token", "empty",
                            "whitespace", "adversarial-unicode");
            assertThat(corpus)
                    .allSatisfy(item -> assertThat(estimator.estimateUnits(item.text()))
                            .as(item.id())
                            .isEqualTo(item.expectedUnits()));
        }
        assertThat(estimator.algorithmVersion()).isEqualTo("apvero-utf8-byte-v1");
    }

    @Test
    void rejectsMissingTextWithAStableCode() {
        assertThatThrownBy(() -> estimator.estimateUnits(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("APVERO_EMBEDDING_ESTIMATOR_TEXT_REQUIRED");
    }

    private record CorpusCase(String id, String category, String text, long expectedUnits) {}
}
