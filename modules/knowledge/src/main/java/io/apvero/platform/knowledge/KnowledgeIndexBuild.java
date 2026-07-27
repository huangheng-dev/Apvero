package io.apvero.platform.knowledge;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeIndexBuild(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        UUID indexId,
        String version,
        UUID embeddingRouteId,
        String embeddingRouteReference,
        Status status,
        int sourceRevisionCount,
        int chunkCount,
        int vectorDimension,
        int attemptCount,
        boolean retryable,
        String validationDigest,
        UUID publishedVersionId,
        String errorCode,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        OffsetDateTime updatedAt) {

    public enum Status {
        QUEUED,
        EMBEDDING,
        INDEXING,
        VALIDATING,
        READY,
        RETRY_WAIT,
        FAILED,
        CANCELLED
    }
}
