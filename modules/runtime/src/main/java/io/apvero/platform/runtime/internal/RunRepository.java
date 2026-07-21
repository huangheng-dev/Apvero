package io.apvero.platform.runtime.internal;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.release.ReleaseBundle;
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
            AiApplication application,
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
            AiApplication application,
            ReleaseBundle release,
            String providerId,
            String actorId,
            ExecutionPermit permit,
            tools.jackson.databind.JsonNode input,
            long latencyMs,
            String traceId,
            String failureCategory,
            String failureMessage);

    UsageSummary summarize(UUID workspaceId);

    int deleteBefore(UUID workspaceId, OffsetDateTime cutoff);
}
