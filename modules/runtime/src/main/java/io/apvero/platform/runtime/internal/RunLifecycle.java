package io.apvero.platform.runtime.internal;

import io.apvero.platform.capability.ChatExecutionPermit;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.runtime.ProviderResult;
import io.apvero.platform.runtime.RunRecord;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class RunLifecycle {
    private final RunRepository repository;

    public RunLifecycle(RunRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public RunRecord begin(
            ReleaseBundle release,
            UUID modelRouteId,
            String actorId,
            JsonNode input,
            String traceId) {
        return repository.insertRunning(release, modelRouteId, actorId, input, traceId);
    }

    @Transactional
    public RunRecord attachChat(
            UUID workspaceId,
            UUID runId,
            String providerId,
            ChatExecutionPermit permit) {
        return repository.attachChat(workspaceId, runId, providerId, permit);
    }

    @Transactional
    public RunRecord succeed(
            UUID workspaceId,
            UUID runId,
            String providerId,
            JsonNode output,
            ProviderResult result,
            long latencyMs) {
        return repository.completeSuccess(
                workspaceId, runId, providerId, output, result, latencyMs);
    }

    @Transactional
    public RunRecord noEvidence(
            UUID workspaceId,
            UUID runId,
            JsonNode output,
            long latencyMs) {
        return repository.completeNoEvidence(workspaceId, runId, output, latencyMs);
    }

    @Transactional
    public RunRecord fail(
            UUID workspaceId,
            UUID runId,
            String providerId,
            JsonNode output,
            ProviderResult result,
            long latencyMs,
            String failureCode,
            String failureCategory,
            String failureMessage) {
        return repository.completeFailure(
                workspaceId,
                runId,
                providerId,
                output,
                result,
                latencyMs,
                failureCode,
                failureCategory,
                failureMessage);
    }
}
