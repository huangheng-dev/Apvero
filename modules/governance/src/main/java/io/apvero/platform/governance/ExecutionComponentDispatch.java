package io.apvero.platform.governance;

import java.util.Objects;
import java.util.UUID;

public record ExecutionComponentDispatch(
        UUID reservationId,
        String idempotencyIdentity,
        String providerRequestIdentity) {

    public ExecutionComponentDispatch {
        Objects.requireNonNull(reservationId, "APVERO_EXECUTION_RESERVATION_ID_REQUIRED");
        if (idempotencyIdentity == null
                || idempotencyIdentity.isBlank()
                || idempotencyIdentity.length() > 200) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_IDEMPOTENCY_INVALID");
        }
        if (providerRequestIdentity != null
                && (providerRequestIdentity.isBlank() || providerRequestIdentity.length() > 200)) {
            throw new IllegalArgumentException("APVERO_EXECUTION_PROVIDER_REQUEST_IDENTITY_INVALID");
        }
        idempotencyIdentity = idempotencyIdentity.trim();
        if (providerRequestIdentity != null) {
            providerRequestIdentity = providerRequestIdentity.trim();
        }
    }
}
