package io.apvero.platform.knowledge;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeIngestionJob(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        UUID knowledgeBaseId,
        UUID sourceId,
        UUID sourceRevisionId,
        Status status,
        Step currentStep,
        int attemptCount,
        boolean retryable,
        SyncOutcome syncOutcome,
        OffsetDateTime nextAttemptAt,
        String errorCode,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime updatedAt) {

    public enum Status {
        QUEUED,
        SNAPSHOTTING,
        PARSING,
        CHUNKING,
        READY,
        RETRY_WAIT,
        FAILED,
        CANCELLED
    }

    public enum Step {
        SNAPSHOTTING,
        PARSING,
        CHUNKING,
        COMPLETE
    }

    public enum SyncOutcome {
        CHANGED,
        UNCHANGED
    }
}
