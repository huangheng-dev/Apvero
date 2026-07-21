package io.apvero.platform.identity;

import java.util.UUID;

public record SecurityDecisionEvent(
        UUID workspaceId,
        String actorId,
        String action,
        String resourceId,
        String sourceIp,
        String reasonCode) {}
