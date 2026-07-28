package io.apvero.platform.knowledge.internal;

import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import java.util.Objects;

record KnowledgeIndexValidationClaimOutcome(
        BuildRow build,
        Status status) {

    KnowledgeIndexValidationClaimOutcome {
        Objects.requireNonNull(build, "APVERO_KNOWLEDGE_BUILD_REQUIRED");
        Objects.requireNonNull(status, "APVERO_KNOWLEDGE_VALIDATION_OUTCOME_REQUIRED");
    }

    enum Status {
        ADVANCED_TO_VALIDATING,
        FAILED_VALIDATION
    }
}
