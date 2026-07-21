package io.apvero.platform.governance;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RetentionPolicy(
        UUID workspaceId,
        UUID tenantId,
        int runRetentionDays,
        int auditRetentionDays,
        boolean retainPayloads,
        boolean maskSensitiveFields,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
