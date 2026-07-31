package io.apvero.platform.capability;

import java.util.UUID;

public record ChatExecutionPermit(
        UUID reservationId,
        UUID modelRouteId,
        String modelRouteReference,
        String componentIdentity,
        long estimatedUnits,
        long estimatedCostMicros,
        String currency,
        ExecutionRetentionDecision retention) {}
