package io.apvero.platform.runtime.internal;

import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.capability.ChatExecutionPermit;
import io.apvero.platform.runtime.ProviderResult;
import io.apvero.platform.runtime.RunRecord;
import io.apvero.platform.runtime.UsageSummary;
import java.util.List;
import java.util.UUID;
import io.apvero.platform.capability.ExecutionPermit;
import java.time.OffsetDateTime;

interface RunRepository {
    List<RunRecord> findAll(UUID workspaceId);

    RunRecord insert(
            ReleaseBundle release,
            String providerId,
            String actorId,
            ExecutionPermit permit,
            tools.jackson.databind.JsonNode input,
            tools.jackson.databind.JsonNode output,
            ProviderResult result,
            long latencyMs,
            String traceId);

    RunRecord insertFailure(
            ReleaseBundle release,
            String providerId,
            String actorId,
            ExecutionPermit permit,
            tools.jackson.databind.JsonNode input,
            long latencyMs,
            String traceId,
            String failureCode,
            String failureCategory,
            String failureMessage);

    RunRecord insertRunning(
            ReleaseBundle release,
            UUID modelRouteId,
            String actorId,
            tools.jackson.databind.JsonNode input,
            String traceId);

    RunRecord attachChat(
            UUID workspaceId,
            UUID runId,
            String providerId,
            ChatExecutionPermit permit);

    RunRecord completeSuccess(
            UUID workspaceId,
            UUID runId,
            String providerId,
            tools.jackson.databind.JsonNode output,
            ProviderResult result,
            long latencyMs);

    RunRecord completeNoEvidence(
            UUID workspaceId,
            UUID runId,
            tools.jackson.databind.JsonNode output,
            long latencyMs);

    RunRecord completeFailure(
            UUID workspaceId,
            UUID runId,
            String providerId,
            tools.jackson.databind.JsonNode output,
            ProviderResult result,
            long latencyMs,
            String failureCode,
            String failureCategory,
            String failureMessage);

    UsageSummary summarize(UUID workspaceId);

    int deleteBefore(UUID workspaceId, OffsetDateTime cutoff);
}
