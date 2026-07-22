package io.apvero.platform.knowledge;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeSource(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        UUID knowledgeBaseId,
        String name,
        Type sourceType,
        Status status,
        int revisionCount,
        UUID latestRevisionId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public enum Type {
        TEXT,
        MARKDOWN,
        PDF,
        DOCX,
        WEB
    }

    public enum Status {
        ACTIVE,
        TOMBSTONED
    }
}
