package io.apvero.platform.governance;

import java.util.UUID;

public record ExecutionAdmission(
        UUID reservationId,
        boolean retainPayloads,
        boolean maskSensitiveFields,
        long retentionDecisionVersion) {

    public ExecutionAdmission(
            UUID reservationId,
            boolean retainPayloads,
            boolean maskSensitiveFields) {
        this(reservationId, retainPayloads, maskSensitiveFields, 1);
    }

    public ExecutionAdmission {
        if (retentionDecisionVersion < 0) {
            throw new IllegalArgumentException("APVERO_EXECUTION_RETENTION_INVALID");
        }
    }
}
