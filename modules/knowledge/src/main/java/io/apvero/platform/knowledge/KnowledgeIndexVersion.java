package io.apvero.platform.knowledge;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeIndexVersion(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        UUID indexId,
        UUID buildId,
        String version,
        String reference,
        UUID embeddingRouteId,
        String embeddingRouteReference,
        int vectorDimension,
        int sourceRevisionCount,
        int chunkCount,
        String artifactDigest,
        Status status,
        OffsetDateTime publishedAt) {

    public enum Status {
        READY
    }
}
