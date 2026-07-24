package io.apvero.platform.governance;

import java.util.Objects;
import java.util.UUID;

public record ExecutionComponentSettlement(
        UUID reservationId,
        String idempotencyIdentity,
        long actualUnits,
        long actualCostMicros,
        String currency,
        boolean succeeded,
        String failureCode) {

    public ExecutionComponentSettlement {
        Objects.requireNonNull(reservationId, "APVERO_EXECUTION_RESERVATION_ID_REQUIRED");
        if (idempotencyIdentity == null
                || idempotencyIdentity.isBlank()
                || idempotencyIdentity.length() > 200) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_IDEMPOTENCY_INVALID");
        }
        if (actualUnits < 0) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_UNITS_INVALID");
        }
        if (actualCostMicros < 0) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_COST_INVALID");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_CURRENCY_INVALID");
        }
        if (succeeded != (failureCode == null)) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_OUTCOME_INVALID");
        }
        if (failureCode != null
                && (failureCode.isBlank()
                || failureCode.length() > 120
                || !failureCode.matches("^APVERO_[A-Z0-9_]+$"))) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_FAILURE_CODE_INVALID");
        }
        idempotencyIdentity = idempotencyIdentity.trim();
    }
}
