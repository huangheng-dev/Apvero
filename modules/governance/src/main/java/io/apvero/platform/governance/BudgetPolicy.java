package io.apvero.platform.governance;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BudgetPolicy(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        String name,
        BudgetScopeType scopeType,
        UUID scopeId,
        Long monthlyCostLimitMicros,
        Integer requestsPerMinute,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
