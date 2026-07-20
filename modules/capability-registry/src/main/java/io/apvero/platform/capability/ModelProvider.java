package io.apvero.platform.capability;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ModelProvider(
        UUID id, UUID tenantId, UUID workspaceId, String name, String providerType, String baseUrl,
        UUID secretReferenceId, boolean enabled, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
