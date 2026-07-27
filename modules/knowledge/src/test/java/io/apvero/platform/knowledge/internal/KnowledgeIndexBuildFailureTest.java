package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KnowledgeIndexBuildFailureTest {
    @Test
    void reconciliationRequiresNonRetryableAmbiguousCategory() {
        assertThatThrownBy(() -> new KnowledgeIndexBuildFailure(
                        "APVERO_TEST",
                        KnowledgeIndexBuildFailure.Category.TRANSIENT,
                        false,
                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_FAILURE_INVALID");
        assertThatThrownBy(() -> new KnowledgeIndexBuildFailure(
                        "APVERO_TEST",
                        KnowledgeIndexBuildFailure.Category.AMBIGUOUS,
                        true,
                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_FAILURE_INVALID");
    }

    @Test
    void errorCodeIsBoundedAndRequired() {
        assertThatThrownBy(() -> new KnowledgeIndexBuildFailure(
                        " ",
                        KnowledgeIndexBuildFailure.Category.INTERNAL,
                        false,
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_FAILURE_INVALID");
    }
}
