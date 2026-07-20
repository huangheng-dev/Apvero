package io.apvero.platform.capability;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PromptAsset(
        UUID id, UUID tenantId, UUID workspaceId, String slug, String name, String description,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
