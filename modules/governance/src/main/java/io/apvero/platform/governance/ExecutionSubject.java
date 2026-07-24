package io.apvero.platform.governance;

import java.util.Objects;
import java.util.UUID;

public record ExecutionSubject(ExecutionSubjectType type, UUID id) {
    public ExecutionSubject {
        Objects.requireNonNull(type, "APVERO_EXECUTION_SUBJECT_TYPE_REQUIRED");
        Objects.requireNonNull(id, "APVERO_EXECUTION_SUBJECT_ID_REQUIRED");
    }

    public static ExecutionSubject applicationRun(UUID applicationId) {
        return new ExecutionSubject(ExecutionSubjectType.APPLICATION_RUN, applicationId);
    }

    public static ExecutionSubject knowledgeIngestion(UUID ingestionId) {
        return new ExecutionSubject(ExecutionSubjectType.KNOWLEDGE_INGESTION, ingestionId);
    }

    public static ExecutionSubject knowledgeQuery(UUID queryId) {
        return new ExecutionSubject(ExecutionSubjectType.KNOWLEDGE_QUERY, queryId);
    }
}
