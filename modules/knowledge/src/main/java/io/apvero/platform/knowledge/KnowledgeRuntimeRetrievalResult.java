package io.apvero.platform.knowledge;

import java.util.Objects;

public record KnowledgeRuntimeRetrievalResult(
        KnowledgeRetrievalResult retrieval,
        long retentionDecisionVersion,
        boolean retainPayloads,
        boolean maskSensitiveFields) {

    public KnowledgeRuntimeRetrievalResult {
        Objects.requireNonNull(retrieval, "retrieval");
        if (retentionDecisionVersion < 1) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_RETENTION_POLICY_INVALID");
        }
    }
}
