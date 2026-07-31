package io.apvero.platform.capability;

import tools.jackson.databind.JsonNode;
import java.util.UUID;

public interface ExecutionCapabilityPolicy {
    ExecutionPermit admit(UUID workspaceId, UUID applicationId, String modelRouteReference,
            String actorId, String traceId, JsonNode input);

    void settle(UUID reservationId, long actualCostMicros, boolean succeeded);

    ExecutionRetentionDecision currentRetention(UUID workspaceId);

    UUID resolveChatRouteId(UUID workspaceId, String modelRouteReference);

    ChatExecutionPermit reserveChat(
            UUID workspaceId,
            UUID applicationId,
            String modelRouteReference,
            String actorId,
            String traceId,
            long estimatedInputUnits);

    void markChatDispatched(ChatExecutionPermit permit, String providerRequestIdentity);

    void settleChat(
            ChatExecutionPermit permit,
            long actualUnits,
            long actualCostMicros,
            boolean succeeded,
            String failureCode);

    void requireChatReconciliation(ChatExecutionPermit permit, String failureCode);
}
