package io.apvero.platform.capability;

import java.util.UUID;

public record ExecutionPermit(
        UUID reservationId,
        UUID modelRouteId,
        boolean retainPayloads,
        boolean maskSensitiveFields) {}
