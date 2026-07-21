package io.apvero.platform.runtime.internal;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleaseCatalog;
import io.apvero.platform.runtime.ExecuteRunCommand;
import io.apvero.platform.runtime.ProviderRequest;
import io.apvero.platform.runtime.ProviderResult;
import io.apvero.platform.runtime.RunCatalog;
import io.apvero.platform.runtime.RunRecord;
import io.apvero.platform.runtime.RuntimeProvider;
import io.apvero.platform.runtime.UsageSummary;
import io.apvero.platform.capability.ExecutionCapabilityPolicy;
import io.apvero.platform.capability.ExecutionPermit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultRunCatalog implements RunCatalog {
    private final ApplicationCatalog applications;
    private final ReleaseCatalog releases;
    private final RuntimeProviderRegistry providers;
    private final RunRepository repository;
    private final ExecutionCapabilityPolicy governance;
    private final MeterRegistry metrics;
    private final ObjectMapper json;

    public DefaultRunCatalog(
            ApplicationCatalog applications,
            ReleaseCatalog releases,
            RuntimeProviderRegistry providers,
            RunRepository repository,
            ExecutionCapabilityPolicy governance,
            MeterRegistry metrics,
            ObjectMapper json) {
        this.applications = applications;
        this.releases = releases;
        this.providers = providers;
        this.repository = repository;
        this.governance = governance;
        this.metrics = metrics;
        this.json = json;
    }

    @Override
    public List<RunRecord> list(UUID workspaceId) {
        return repository.findAll(workspaceId);
    }

    @Override
    @Transactional
    public RunRecord execute(UUID workspaceId, UUID applicationId, ExecuteRunCommand command) {
        AiApplication application = applications.get(workspaceId, applicationId);
        ReleaseBundle release = releases.get(workspaceId, command.releaseId());
        if (!release.applicationId().equals(application.id())) {
            throw new IllegalArgumentException("The release does not belong to the requested application.");
        }
        if (release.purpose() == io.apvero.platform.release.ReleasePurpose.PREVIEW
                && release.expiresAt() != null && release.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException("The preview execution bundle has expired.");
        }
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String routeReference = release.manifest().path("modelRouteVersion").stringValue();
        ExecutionPermit permit = governance.admit(workspaceId, applicationId, routeReference,
                command.actorId(), traceId, command.input());
        JsonNode storedInput = retained(command.input(), permit);
        long started = System.nanoTime();
        RuntimeProvider provider = null;
        try {
            provider = providers.resolve(release);
            ProviderResult result = provider.execute(new ProviderRequest(release, command.input(), traceId));
            long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            RunRecord run = repository.insert(application, release, provider.id(), command.actorId(), permit,
                    storedInput, retained(result.output(), permit), result, latencyMs, traceId);
            governance.settle(permit.reservationId(), result.costMicros(), true);
            recordMetrics(run);
            return run;
        } catch (RuntimeException exception) {
            long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            String category = exception instanceof IllegalArgumentException ? "INVALID_CONFIGURATION" : "PROVIDER_FAILURE";
            RunRecord run = repository.insertFailure(application, release, provider == null ? "unresolved" : provider.id(),
                    command.actorId(), permit, storedInput, latencyMs, traceId, category, "Provider execution failed.");
            governance.settle(permit.reservationId(), 0, false);
            recordMetrics(run);
            return run;
        }
    }

    @Override
    public UsageSummary usage(UUID workspaceId) {
        return repository.summarize(workspaceId);
    }

    @Override
    @Transactional
    public int purgeBefore(UUID workspaceId, OffsetDateTime cutoff) {
        return repository.deleteBefore(workspaceId, cutoff);
    }

    private JsonNode retained(JsonNode payload, ExecutionPermit permit) {
        if (!permit.retainPayloads()) return json.createObjectNode().put("retained", false);
        return permit.maskSensitiveFields() ? mask(payload) : payload.deepCopy();
    }

    private JsonNode mask(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            node.properties().forEach(entry -> result.set(entry.getKey(), isSensitive(entry.getKey())
                    ? json.getNodeFactory().textNode("***") : mask(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.valueStream().forEach(value -> result.add(mask(value)));
            return result;
        }
        return node.deepCopy();
    }

    private boolean isSensitive(String key) {
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("apikey") || normalized.contains("authorization");
    }

    private void recordMetrics(RunRecord run) {
        String outcome = run.status().name().toLowerCase(java.util.Locale.ROOT);
        metrics.counter("apvero.ai.runs", "outcome", outcome, "provider", run.providerId()).increment();
        metrics.counter("apvero.ai.tokens", "type", "prompt").increment(run.promptTokens());
        metrics.counter("apvero.ai.tokens", "type", "completion").increment(run.completionTokens());
        metrics.counter("apvero.ai.cost.micros").increment(run.costMicros());
        Timer.builder("apvero.ai.run.latency").tag("outcome", outcome).register(metrics)
                .record(java.time.Duration.ofMillis(run.latencyMs()));
    }
}
