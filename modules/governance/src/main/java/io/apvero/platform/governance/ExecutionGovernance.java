package io.apvero.platform.governance;

import java.util.UUID;

public interface ExecutionGovernance {
    ExecutionAdmission admit(UUID workspaceId, UUID applicationId, UUID modelRouteId,
            String actorId, String traceId, long estimatedCostMicros);

    default ExecutionAdmission admit(ExecutionReservationRequest request) {
        if (request.subject().type() != ExecutionSubjectType.APPLICATION_RUN
                || request.components().size() != 1
                || request.components().getFirst().type() != ExecutionComponentType.CHAT_GENERATION) {
            throw new UnsupportedOperationException("APVERO_GOVERNANCE_COMPONENT_RESERVATION_DISABLED");
        }
        ExecutionComponentRequest component = request.components().getFirst();
        return admit(request.workspaceId(), request.subject().id(), component.modelRouteId(),
                request.actorId(), request.traceId(), component.estimatedCostMicros());
    }

    default void markDispatched(ExecutionComponentDispatch dispatch) {
        throw new UnsupportedOperationException("APVERO_GOVERNANCE_COMPONENT_DISPATCH_DISABLED");
    }

    default void settle(ExecutionComponentSettlement settlement) {
        throw new UnsupportedOperationException("APVERO_GOVERNANCE_COMPONENT_SETTLEMENT_DISABLED");
    }

    void settle(UUID reservationId, long actualCostMicros, boolean succeeded);
}
