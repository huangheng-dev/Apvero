package io.apvero.platform.governance;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SecretReference(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        String name,
        String kind,
        String locator,
        String status,
        OffsetDateTime rotatedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
