package io.apvero.platform.governance;

import java.util.Objects;
import java.util.UUID;

public record ExecutionComponentReconciliation(
        UUID reservationId,
        String idempotencyIdentity,
        String failureCode) {

    public ExecutionComponentReconciliation {
        Objects.requireNonNull(reservationId, "APVERO_EXECUTION_RESERVATION_ID_REQUIRED");
        if (idempotencyIdentity == null
                || idempotencyIdentity.isBlank()
                || idempotencyIdentity.length() > 200) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_IDEMPOTENCY_INVALID");
        }
        if (failureCode == null
                || failureCode.isBlank()
                || failureCode.length() > 120
                || !failureCode.matches("^APVERO_[A-Z0-9_]+$")) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_FAILURE_CODE_INVALID");
        }
        idempotencyIdentity = idempotencyIdentity.trim();
    }
}
