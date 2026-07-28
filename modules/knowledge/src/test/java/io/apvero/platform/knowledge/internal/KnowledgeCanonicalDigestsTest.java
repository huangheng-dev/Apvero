package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeCanonicalDigestsTest {

    @Test
    void preservesTextVectorAndStableIdentityCompatibilityFixtures() {
        assertThat(KnowledgeCanonicalDigests.text("first"))
                .isEqualTo("sha256:a7937b64b8caa58f03721bb6bacf5c78cb235febe0e70b1b84cd99541461a08e");
        assertThat(KnowledgeCanonicalDigests.vector(List.of(1f, -0.0f, 0.5f)))
                .isEqualTo("sha256:1c42b7aff4b2c80e69d068174c70f712d9706eacfc06191efebdeb0ca8a5bec2");
        assertThat(KnowledgeCanonicalDigests.stableId(
                        "apvero:knowledge-index-entry:"
                                + "00000000-0000-0000-0000-000000000007:"
                                + "00000000-0000-0000-0000-000000000012"))
                .isEqualTo(UUID.fromString("98bd1d79-33d7-53f6-b091-822fefac32ec"));
    }

    @Test
    void lengthPrefixEncodingSeparatesAmbiguousFieldConcatenations() {
        assertThat(digest("ab", "c")).isNotEqualTo(digest("a", "bc"));
    }

    @Test
    void rejectsEmptyNonFiniteAndZeroNormVectors() {
        assertThatThrownBy(() -> KnowledgeCanonicalDigests.vector(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_VECTOR_INTEGRITY_INVALID");
        assertThatThrownBy(() -> KnowledgeCanonicalDigests.vector(Arrays.asList(1f, Float.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_VECTOR_INTEGRITY_INVALID");
        assertThatThrownBy(() -> KnowledgeCanonicalDigests.vector(List.of(0f, -0.0f)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_VECTOR_INTEGRITY_INVALID");
    }

    private static String digest(String... values) {
        KnowledgeCanonicalDigests.DigestBuilder builder =
                KnowledgeCanonicalDigests.builder("test-domain");
        for (String value : values) {
            builder.addString(value);
        }
        return builder.finish();
    }
}
