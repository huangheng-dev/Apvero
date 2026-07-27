package io.apvero.platform.knowledge.internal;

record KnowledgeIndexBuildFailure(
        String code,
        Category category,
        boolean retryable,
        boolean reconciliationRequired) {
    KnowledgeIndexBuildFailure {
        if (code == null || code.isBlank() || code.length() > 120 || category == null) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_INDEX_BUILD_FAILURE_INVALID");
        }
        code = code.trim();
        if (reconciliationRequired && category != Category.AMBIGUOUS) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_INDEX_BUILD_FAILURE_INVALID");
        }
        if (reconciliationRequired && retryable) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_INDEX_BUILD_FAILURE_INVALID");
        }
    }

    enum Category {
        VALIDATION,
        SECURITY,
        TRANSIENT,
        PERMANENT,
        INTERNAL,
        AMBIGUOUS
    }
}
