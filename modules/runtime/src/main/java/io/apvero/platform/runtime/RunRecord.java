package io.apvero.platform.runtime;

import tools.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RunRecord(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        UUID applicationId,
        UUID releaseBundleId,
        RunStatus status,
        String providerId,
        JsonNode input,
        JsonNode output,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        long costMicros,
        String traceId,
        String failureCategory,
        String failureMessage,
        OffsetDateTime createdAt) {}
