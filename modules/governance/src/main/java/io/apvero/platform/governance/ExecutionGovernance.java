package io.apvero.platform.governance;

import java.util.UUID;

public interface ExecutionGovernance {
    ExecutionAdmission admit(UUID workspaceId, UUID applicationId, UUID modelRouteId,
            String actorId, String traceId, long estimatedCostMicros);

    void settle(UUID reservationId, long actualCostMicros, boolean succeeded);
}
