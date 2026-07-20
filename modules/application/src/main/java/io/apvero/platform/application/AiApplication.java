package io.apvero.platform.application;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AiApplication(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        String slug,
        String name,
        String description,
        RuntimeMode runtimeMode,
        ApplicationStatus status,
        UUID draftModelRouteId,
        UUID draftPromptVersionId,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
