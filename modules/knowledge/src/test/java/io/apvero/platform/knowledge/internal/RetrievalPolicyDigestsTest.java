package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RetrievalPolicyDigestsTest {
    @Test
    void isLocaleIndependentAndCanonicalAcrossEquivalentDecimals() {
        String left = digest(new BigDecimal("0.700000"));
        String right = digest(new BigDecimal("0.7"));

        assertThat(left).isEqualTo(right);
        assertThat(left).matches("^sha256:[a-f0-9]{64}$");
    }

    @Test
    void changesWhenAnyBehaviorOrProvenanceChanges() {
        String baseline = digest(new BigDecimal("0.7"));

        assertThat(RetrievalPolicyDigests.canonical(
                "exact-cosine@1.0.0",
                "apvero-utf8-byte@1.0.0",
                2,
                8,
                4096,
                new BigDecimal("0.7"),
                "KEEP",
                "NO_EVIDENCE")).isNotEqualTo(baseline);
        assertThat(RetrievalPolicyDigests.canonical(
                "exact-cosine@1.0.0",
                "apvero-utf8-byte@1.0.0",
                1,
                9,
                4096,
                new BigDecimal("0.7"),
                "KEEP",
                "NO_EVIDENCE")).isNotEqualTo(baseline);
    }

    private static String digest(BigDecimal score) {
        return RetrievalPolicyDigests.canonical(
                "exact-cosine@1.0.0",
                "apvero-utf8-byte@1.0.0",
                1,
                8,
                4096,
                score,
                "KEEP",
                "NO_EVIDENCE");
    }
}
