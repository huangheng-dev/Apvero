package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingReplayPolicy;
import java.util.Set;

final class KnowledgeEmbeddingFailureNormalizer {
    private static final Set<String> VALIDATION_CODES = Set.of(
            "APVERO_EMBEDDING_ROUTE_REFERENCE_MISMATCH",
            "APVERO_EMBEDDING_OUTPUT_MAPPING_INVALID",
            "APVERO_EMBEDDING_OUTPUT_DIMENSION_MISMATCH",
            "APVERO_EMBEDDING_VECTOR_NON_FINITE",
            "APVERO_EMBEDDING_VECTOR_ZERO_NORM",
            "APVERO_EMBEDDING_DIMENSION_MISMATCH");

    private KnowledgeEmbeddingFailureNormalizer() {}

    static KnowledgeIndexBuildFailure normalize(
            RuntimeException failure,
            EmbeddingReplayPolicy replayPolicy) {
        String code = stableCode(failure);
        if ("APVERO_EMBEDDING_PROVIDER_TIMEOUT".equals(code)
                && replayPolicy == EmbeddingReplayPolicy.RECONCILIATION_REQUIRED) {
            return new KnowledgeIndexBuildFailure(
                    "APVERO_EMBEDDING_OUTCOME_AMBIGUOUS",
                    KnowledgeIndexBuildFailure.Category.AMBIGUOUS,
                    false,
                    true);
        }
        if ("APVERO_EMBEDDING_PROVIDER_TIMEOUT".equals(code)) {
            return new KnowledgeIndexBuildFailure(
                    code, KnowledgeIndexBuildFailure.Category.TRANSIENT, true, false);
        }
        if ("APVERO_EMBEDDING_SECRET_UNAVAILABLE".equals(code)) {
            return new KnowledgeIndexBuildFailure(
                    code, KnowledgeIndexBuildFailure.Category.SECURITY, false, false);
        }
        if ("APVERO_EMBEDDING_PROVIDER_REJECTED".equals(code)) {
            return new KnowledgeIndexBuildFailure(
                    code, KnowledgeIndexBuildFailure.Category.PERMANENT, false, false);
        }
        if (VALIDATION_CODES.contains(code) || failure instanceof IllegalArgumentException) {
            return new KnowledgeIndexBuildFailure(
                    VALIDATION_CODES.contains(code)
                            ? code
                            : "APVERO_EMBEDDING_RESULT_INVALID",
                    KnowledgeIndexBuildFailure.Category.VALIDATION,
                    false,
                    false);
        }
        return new KnowledgeIndexBuildFailure(
                "APVERO_EMBEDDING_INTERNAL",
                KnowledgeIndexBuildFailure.Category.INTERNAL,
                false,
                false);
    }

    private static String stableCode(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && message.matches("^APVERO_[A-Z0-9_]{1,112}$")
                ? message
                : "APVERO_EMBEDDING_INTERNAL";
    }
}
