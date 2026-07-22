package io.apvero.platform.knowledge;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeSourceRevision(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        UUID sourceId,
        int revision,
        String contentDigest,
        String mediaType,
        long byteSize,
        SnapshotStatus snapshotStatus,
        String parserVersion,
        String chunkerVersion,
        OffsetDateTime createdAt) {

    public enum SnapshotStatus {
        SNAPSHOTTED,
        REJECTED
    }
}
