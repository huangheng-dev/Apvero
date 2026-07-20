package io.apvero.platform.capability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ModelDefinition(
        UUID id, UUID tenantId, UUID workspaceId, UUID providerId, String modelKey, String name,
        List<String> capabilities, long inputCostMicrosPerMillion, long outputCostMicrosPerMillion,
        boolean enabled, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
