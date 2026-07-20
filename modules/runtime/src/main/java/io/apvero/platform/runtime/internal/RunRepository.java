package io.apvero.platform.runtime.internal;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.runtime.ProviderResult;
import io.apvero.platform.runtime.RunRecord;
import io.apvero.platform.runtime.UsageSummary;
import java.util.List;
import java.util.UUID;

interface RunRepository {
    List<RunRecord> findAll(UUID workspaceId);

    RunRecord insert(
            AiApplication application,
            ReleaseBundle release,
            String providerId,
            tools.jackson.databind.JsonNode input,
            ProviderResult result,
            long latencyMs,
            String traceId);

    RunRecord insertFailure(
            AiApplication application,
            ReleaseBundle release,
            String providerId,
            tools.jackson.databind.JsonNode input,
            long latencyMs,
            String traceId,
            String failureCategory,
            String failureMessage);

    UsageSummary summarize(UUID workspaceId);
}
