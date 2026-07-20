package io.apvero.platform.identity;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record ApiCredential(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        String name,
        String prefix,
        Set<String> scopes,
        String status,
        OffsetDateTime expiresAt,
        OffsetDateTime lastUsedAt,
        OffsetDateTime createdAt) {}
