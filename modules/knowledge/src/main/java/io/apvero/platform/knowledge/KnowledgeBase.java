package io.apvero.platform.knowledge;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeBase(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        String slug,
        String name,
        String description,
        Status status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public enum Status {
        ACTIVE,
        ARCHIVED
    }
}
