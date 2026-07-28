package io.apvero.platform.governance;

import java.util.Objects;
import java.util.UUID;

public record ExecutionComponentSnapshot(
        UUID reservationId,
        ExecutionComponentType type,
        UUID modelRouteId,
        String modelRouteReference,
        String idempotencyIdentity,
        long estimatedUnits,
        Long actualUnits,
        long estimatedCostMicros,
        Long actualCostMicros,
        String currency,
        ExecutionUsageQuality usageQuality,
        ExecutionComponentState state,
        String providerRequestIdentity,
        String failureCode) {

    public ExecutionComponentSnapshot {
        Objects.requireNonNull(reservationId, "APVERO_EXECUTION_RESERVATION_ID_REQUIRED");
        Objects.requireNonNull(type, "APVERO_EXECUTION_COMPONENT_TYPE_REQUIRED");
        Objects.requireNonNull(modelRouteId, "APVERO_EXECUTION_COMPONENT_ROUTE_ID_REQUIRED");
        Objects.requireNonNull(state, "APVERO_EXECUTION_COMPONENT_STATE_REQUIRED");
        if (modelRouteReference == null || modelRouteReference.isBlank()) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_ROUTE_REFERENCE_REQUIRED");
        }
        if (idempotencyIdentity == null || idempotencyIdentity.isBlank()) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_IDEMPOTENCY_INVALID");
        }
        if (estimatedUnits < 0
                || estimatedCostMicros < 0
                || (actualUnits != null && actualUnits < 0)
                || (actualCostMicros != null && actualCostMicros < 0)) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_USAGE_INVALID");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_CURRENCY_INVALID");
        }
        if ((actualUnits == null) != (actualCostMicros == null)
                || (actualUnits == null) != (usageQuality == null)) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_USAGE_INVALID");
        }
        modelRouteReference = modelRouteReference.trim();
        idempotencyIdentity = idempotencyIdentity.trim();
    }
}
