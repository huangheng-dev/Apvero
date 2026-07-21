package io.apvero.platform.capability;

import java.util.UUID;

public record ModelRouteReadiness(
        UUID routeId,
        String routeReference,
        String providerName,
        String providerType,
        String status,
        boolean ready,
        String reasonCode) {}
