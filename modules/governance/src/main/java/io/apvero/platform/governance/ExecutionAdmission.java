package io.apvero.platform.governance;

import java.util.UUID;

public record ExecutionAdmission(
        UUID reservationId,
        boolean retainPayloads,
        boolean maskSensitiveFields) {}
