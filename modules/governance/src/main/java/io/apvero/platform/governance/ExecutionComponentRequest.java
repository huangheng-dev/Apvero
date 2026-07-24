package io.apvero.platform.governance;

import java.util.Objects;
import java.util.UUID;

public record ExecutionComponentRequest(
        ExecutionComponentType type,
        UUID modelRouteId,
        String modelRouteReference,
        String idempotencyIdentity,
        long estimatedUnits,
        long estimatedCostMicros,
        String currency) {

    public ExecutionComponentRequest {
        Objects.requireNonNull(type, "APVERO_EXECUTION_COMPONENT_TYPE_REQUIRED");
        Objects.requireNonNull(modelRouteId, "APVERO_EXECUTION_COMPONENT_ROUTE_ID_REQUIRED");
        requireRouteReference(modelRouteReference);
        if (idempotencyIdentity == null
                || idempotencyIdentity.isBlank()
                || idempotencyIdentity.length() > 200) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_IDEMPOTENCY_INVALID");
        }
        if (estimatedUnits < 0) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_UNITS_INVALID");
        }
        if (estimatedCostMicros < 0) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_COST_INVALID");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_CURRENCY_INVALID");
        }
        modelRouteReference = modelRouteReference.trim();
        idempotencyIdentity = idempotencyIdentity.trim();
    }

    private static void requireRouteReference(String reference) {
        if (reference == null) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_ROUTE_REFERENCE_REQUIRED");
        }
        int separator = reference.lastIndexOf('@');
        if (separator < 1 || separator == reference.length() - 1) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_ROUTE_REFERENCE_INVALID");
        }
        try {
            if (Long.parseLong(reference.substring(separator + 1)) < 1) {
                throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_ROUTE_REFERENCE_INVALID");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "APVERO_EXECUTION_COMPONENT_ROUTE_REFERENCE_INVALID", exception);
        }
    }
}
