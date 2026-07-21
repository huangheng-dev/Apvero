package io.apvero.platform.governance;

import java.util.UUID;

public record CreateBudgetPolicyCommand(
        String name,
        BudgetScopeType scopeType,
        UUID scopeId,
        Long monthlyCostLimitMicros,
        Integer requestsPerMinute) {}
