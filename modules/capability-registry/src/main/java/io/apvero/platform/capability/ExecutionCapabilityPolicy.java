package io.apvero.platform.capability;

import tools.jackson.databind.JsonNode;
import java.util.UUID;

public interface ExecutionCapabilityPolicy {
    ExecutionPermit admit(UUID workspaceId, UUID applicationId, String modelRouteReference,
            String actorId, String traceId, JsonNode input);

    void settle(UUID reservationId, long actualCostMicros, boolean succeeded);
}
