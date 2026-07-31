package io.apvero.platform.runtime.internal;

import io.apvero.platform.capability.CapabilityExecutionException;
import io.apvero.platform.capability.ChatExecutionPermit;
import io.apvero.platform.capability.ExecutionCapabilityPolicy;
import io.apvero.platform.capability.ExecutionRetentionDecision;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeDisabledException;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeIndexVersion;
import io.apvero.platform.knowledge.KnowledgeIndexVersionCatalog;
import io.apvero.platform.knowledge.KnowledgeRuntimeRetrieval;
import io.apvero.platform.knowledge.KnowledgeRuntimeRetrievalResult;
import io.apvero.platform.knowledge.RetrievalPolicyVersion;
import io.apvero.platform.knowledge.RetrievalPolicyVersionCatalog;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.runtime.ExecuteRunCommand;
import io.apvero.platform.runtime.GroundingContext;
import io.apvero.platform.runtime.ProviderExecutionException;
import io.apvero.platform.runtime.ProviderFailureDisposition;
import io.apvero.platform.runtime.ProviderRequest;
import io.apvero.platform.runtime.ProviderResult;
import io.apvero.platform.runtime.RecordRetrievalEvidenceCommand;
import io.apvero.platform.runtime.RunRecord;
import io.apvero.platform.runtime.RunRetrievalEvidenceCatalog;
import io.apvero.platform.runtime.RuntimeProvider;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class GroundedRunOrchestrator {
    private final KnowledgeIndexVersionCatalog indexes;
    private final RetrievalPolicyVersionCatalog policies;
    private final KnowledgeRuntimeRetrieval retrieval;
    private final RunRetrievalEvidenceCatalog evidence;
    private final ExecutionCapabilityPolicy governance;
    private final RuntimeProviderRegistry providers;
    private final RunLifecycle lifecycle;
    private final GroundedAnswerFinalizer finalizer;
    private final RuntimePayloadRetention retention;
    private final ObjectMapper json;
    private final MeterRegistry metrics;

    public GroundedRunOrchestrator(
            KnowledgeIndexVersionCatalog indexes,
            RetrievalPolicyVersionCatalog policies,
            KnowledgeRuntimeRetrieval retrieval,
            RunRetrievalEvidenceCatalog evidence,
            ExecutionCapabilityPolicy governance,
            RuntimeProviderRegistry providers,
            RunLifecycle lifecycle,
            GroundedAnswerFinalizer finalizer,
            RuntimePayloadRetention retention,
            ObjectMapper json,
            MeterRegistry metrics) {
        this.indexes = indexes;
        this.policies = policies;
        this.retrieval = retrieval;
        this.evidence = evidence;
        this.governance = governance;
        this.providers = providers;
        this.lifecycle = lifecycle;
        this.finalizer = finalizer;
        this.retention = retention;
        this.json = json;
        this.metrics = metrics;
    }

    public RunRecord execute(
            ReleaseBundle release,
            ExecuteRunCommand command,
            String traceId,
            long startedAtNanos) {
        ExecutionRetentionDecision initialRetention =
                governance.currentRetention(release.workspaceId());
        UUID routeId = governance.resolveChatRouteId(
                release.workspaceId(),
                release.manifest().path("modelRouteVersion").stringValue());
        RunRecord running = lifecycle.begin(
                release,
                routeId,
                command.actorId(),
                retention.apply(command.input(), initialRetention),
                traceId);
        GroundingContextBudget budget = new GroundingContextBudget(json);
        try {
            retrieveOrdered(release, command, traceId, running.id(), budget);
        } catch (RuntimeException failure) {
            return failRetrieval(release, running.id(), failure, startedAtNanos);
        }
        if (budget.isEmpty()) {
            ObjectNode output = json.createObjectNode();
            output.put("schemaVersion", "1.0");
            output.put("status", "NO_EVIDENCE");
            output.put("answer", "");
            output.putArray("citations");
            RunRecord completed = lifecycle.noEvidence(
                    release.workspaceId(),
                    running.id(),
                    retention.apply(output, initialRetention),
                    elapsedMillis(startedAtNanos));
            metrics.counter(
                    "apvero.runtime.grounded.outcomes",
                    "outcome",
                    "no_evidence").increment();
            return completed;
        }
        GroundingContext context = budget.build();
        metrics.summary("apvero.runtime.grounded.context.hits")
                .record(context.hitCount());
        metrics.summary("apvero.runtime.grounded.context.input.units")
                .record(context.estimatedInputUnits());
        return generate(
                release,
                command,
                traceId,
                running.id(),
                context,
                startedAtNanos);
    }

    private void retrieveOrdered(
            ReleaseBundle release,
            ExecuteRunCommand command,
            String traceId,
            UUID runId,
            GroundingContextBudget budget) {
        JsonNode bindings = release.manifest().path("knowledgeBindings");
        if (!bindings.isArray() || bindings.isEmpty() || bindings.size() > 16) {
            throw new IllegalArgumentException(
                    "APVERO_RELEASE_KNOWLEDGE_BINDING_INVALID");
        }
        String query = query(command.input());
        for (int sequence = 0; sequence < bindings.size(); sequence++) {
            JsonNode binding = bindings.get(sequence);
            String indexReference = requiredReference(binding, "indexVersion");
            String policyReference =
                    requiredReference(binding, "retrievalPolicyVersion");
            KnowledgeIndexVersion index =
                    indexes.getByReference(release.workspaceId(), indexReference);
            RetrievalPolicyVersion policy =
                    policies.getByReference(release.workspaceId(), policyReference);
            requireExactBinding(release, indexReference, policyReference, index, policy);
            KnowledgeRuntimeRetrievalResult governed = retrieval.retrieveForRun(
                    release.workspaceId(),
                    new KnowledgeCommandContext(
                            command.actorId(),
                            null,
                            retrievalTraceId(traceId, sequence)),
                    index.id(),
                    policy.id(),
                    query);
            requireResolvedResult(index, policy, governed);
            var budgeted = budget.accept(governed.retrieval());
            evidence.record(
                    release.workspaceId(),
                    runId,
                    new RecordRetrievalEvidenceCommand(
                            sequence,
                            indexReference,
                            policyReference,
                            governed.retentionDecisionVersion(),
                            governed.retainPayloads(),
                            governed.maskSensitiveFields(),
                            budgeted));
            metrics.counter(
                    "apvero.runtime.grounded.retrievals",
                    "outcome",
                    budgeted.status().name().toLowerCase(java.util.Locale.ROOT))
                    .increment();
            metrics.summary("apvero.runtime.grounded.retrieval.hits")
                    .record(budgeted.hits().size());
        }
    }

    private static String retrievalTraceId(String runTraceId, int sequence) {
        return runTraceId + ":rag:" + sequence;
    }

    private RunRecord generate(
            ReleaseBundle release,
            ExecuteRunCommand command,
            String traceId,
            UUID runId,
            GroundingContext context,
            long startedAtNanos) {
        RuntimeProvider provider;
        try {
            provider = providers.resolve(release);
        } catch (RuntimeException failure) {
            return fail(
                    release,
                    runId,
                    "unresolved",
                    emptyOutput(),
                    zeroResult(),
                    elapsedMillis(startedAtNanos),
                    "APVERO_RUNTIME_CONFIGURATION_INVALID",
                    "INVALID_CONFIGURATION");
        }
        ChatExecutionPermit permit;
        try {
            long inputUnits = estimateInputUnits(command.input(), context);
            permit = governance.reserveChat(
                    release.workspaceId(),
                    release.applicationId(),
                    release.manifest().path("modelRouteVersion").stringValue(),
                    command.actorId(),
                    traceId,
                    inputUnits);
        } catch (CapabilityExecutionException failure) {
            return fail(
                    release,
                    runId,
                    provider.id(),
                    emptyOutput(),
                    zeroResult(),
                    elapsedMillis(startedAtNanos),
                    failure.code(),
                    "GOVERNANCE_FAILURE");
        } catch (RuntimeException failure) {
            return fail(
                    release,
                    runId,
                    provider.id(),
                    emptyOutput(),
                    zeroResult(),
                    elapsedMillis(startedAtNanos),
                    "APVERO_RUNTIME_CHAT_RESERVATION_FAILED",
                    "GOVERNANCE_FAILURE");
        }
        try {
            lifecycle.attachChat(
                    release.workspaceId(), runId, provider.id(), permit);
            governance.markChatDispatched(permit, null);
        } catch (CapabilityExecutionException failure) {
            return failBeforeProviderDispatch(
                    release, runId, provider.id(), permit, failure.code(), startedAtNanos);
        } catch (RuntimeException failure) {
            return failBeforeProviderDispatch(
                    release,
                    runId,
                    provider.id(),
                    permit,
                    "APVERO_RUNTIME_CHAT_DISPATCH_PREPARATION_FAILED",
                    startedAtNanos);
        }

        ProviderResult result;
        try {
            result = provider.execute(
                    new ProviderRequest(release, command.input(), traceId, context));
        } catch (RuntimeException failure) {
            return handleProviderFailure(
                    release,
                    runId,
                    provider,
                    permit,
                    failure,
                    startedAtNanos);
        }
        try {
            governance.settleChat(
                    permit,
                    Math.addExact(result.promptTokens(), result.completionTokens()),
                    result.costMicros(),
                    true,
                    null);
        } catch (RuntimeException settlementFailure) {
            return fail(
                    release,
                    runId,
                    provider.id(),
                    retention.apply(result.output(), permit.retention()),
                    result,
                    elapsedMillis(startedAtNanos),
                    "APVERO_RUNTIME_SETTLEMENT_CONFLICT",
                    "SETTLEMENT_FAILURE");
        }
        try {
            RunRecord completed = finalizer.complete(
                    release.workspaceId(),
                    runId,
                    provider.id(),
                    result,
                    permit.retention(),
                    elapsedMillis(startedAtNanos));
            metrics.counter(
                    "apvero.runtime.grounded.outcomes",
                    "outcome",
                    "grounded").increment();
            return completed;
        } catch (GroundedOutputValidationException failure) {
            RunRecord failed = fail(
                    release,
                    runId,
                    provider.id(),
                    emptyOutput(),
                    result,
                    elapsedMillis(startedAtNanos),
                    failure.code(),
                    failure.code().equals("APVERO_CITATION_VALIDATION_FAILED")
                            ? "CITATION_VALIDATION_FAILED"
                            : "GROUNDED_OUTPUT_INVALID");
            metrics.counter(
                    "apvero.runtime.grounded.outcomes",
                    "outcome",
                    failure.code().equals("APVERO_CITATION_VALIDATION_FAILED")
                            ? "citation_invalid"
                            : "output_invalid").increment();
            return failed;
        } catch (RuntimeException failure) {
            RunRecord failed = fail(
                    release,
                    runId,
                    provider.id(),
                    emptyOutput(),
                    result,
                    elapsedMillis(startedAtNanos),
                    "APVERO_RUNTIME_GROUNDED_FINALIZATION_FAILED",
                    "FINALIZATION_FAILURE");
            metrics.counter(
                    "apvero.runtime.grounded.outcomes",
                    "outcome",
                    "finalization_failure").increment();
            return failed;
        }
    }

    private RunRecord failBeforeProviderDispatch(
            ReleaseBundle release,
            UUID runId,
            String providerId,
            ChatExecutionPermit permit,
            String failureCode,
            long startedAtNanos) {
        String code = failureCode;
        try {
            governance.settleChat(permit, 0, 0, false, failureCode);
        } catch (RuntimeException settlementFailure) {
            code = "APVERO_RUNTIME_SETTLEMENT_CONFLICT";
        }
        return fail(
                release,
                runId,
                providerId,
                emptyOutput(),
                zeroResult(),
                elapsedMillis(startedAtNanos),
                code,
                "GOVERNANCE_FAILURE");
    }

    private RunRecord handleProviderFailure(
            ReleaseBundle release,
            UUID runId,
            RuntimeProvider provider,
            ChatExecutionPermit permit,
            RuntimeException failure,
            long startedAtNanos) {
        ProviderFailureDisposition disposition =
                failure instanceof ProviderExecutionException providerFailure
                        ? providerFailure.disposition()
                        : provider.failureDisposition(failure);
        String code;
        if (disposition == ProviderFailureDisposition.RECONCILIATION_REQUIRED) {
            code = "APVERO_EXTERNAL_OUTCOME_RECONCILIATION_REQUIRED";
            try {
                governance.requireChatReconciliation(permit, code);
            } catch (RuntimeException ledgerFailure) {
                code = "APVERO_RUNTIME_SETTLEMENT_CONFLICT";
            }
        } else {
            code = failure instanceof ProviderExecutionException providerFailure
                    ? providerFailure.code()
                    : "APVERO_RUNTIME_PROVIDER_FAILURE";
            try {
                governance.settleChat(permit, 0, 0, false, code);
            } catch (RuntimeException ledgerFailure) {
                code = "APVERO_RUNTIME_SETTLEMENT_CONFLICT";
            }
        }
        RunRecord failed = fail(
                release,
                runId,
                provider.id(),
                emptyOutput(),
                zeroResult(),
                elapsedMillis(startedAtNanos),
                code,
                disposition == ProviderFailureDisposition.RECONCILIATION_REQUIRED
                        ? "RECONCILIATION_REQUIRED"
                        : "PROVIDER_FAILURE");
        metrics.counter(
                "apvero.runtime.grounded.outcomes",
                "outcome",
                disposition == ProviderFailureDisposition.RECONCILIATION_REQUIRED
                        ? "reconciliation_required"
                        : "provider_failure").increment();
        return failed;
    }

    private RunRecord failRetrieval(
            ReleaseBundle release,
            UUID runId,
            RuntimeException failure,
            long startedAtNanos) {
        String code;
        if (failure instanceof KnowledgeDisabledException) {
            code = "APVERO_KNOWLEDGE_DISABLED";
        } else if (failure instanceof KnowledgeException knowledgeFailure) {
            code = switch (knowledgeFailure.code()) {
                case "APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND",
                        "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_VERSION_NOT_FOUND" ->
                        knowledgeFailure.code();
                default -> "APVERO_RUNTIME_RETRIEVAL_FAILED";
            };
        } else if (failure instanceof IllegalArgumentException
                && failure.getMessage() != null
                && failure.getMessage().startsWith("APVERO_RELEASE_")) {
            code = failure.getMessage();
        } else {
            code = "APVERO_RUNTIME_RETRIEVAL_FAILED";
        }
        RunRecord failed = fail(
                release,
                runId,
                "none",
                emptyOutput(),
                zeroResult(),
                elapsedMillis(startedAtNanos),
                code,
                "RETRIEVAL_FAILURE");
        metrics.counter(
                "apvero.runtime.grounded.outcomes",
                "outcome",
                "retrieval_failure").increment();
        return failed;
    }

    private RunRecord fail(
            ReleaseBundle release,
            UUID runId,
            String providerId,
            JsonNode output,
            ProviderResult result,
            long latencyMs,
            String code,
            String category) {
        return lifecycle.fail(
                release.workspaceId(),
                runId,
                providerId,
                output,
                result,
                latencyMs,
                code,
                category,
                code);
    }

    private static void requireExactBinding(
            ReleaseBundle release,
            String indexReference,
            String policyReference,
            KnowledgeIndexVersion index,
            RetrievalPolicyVersion policy) {
        boolean valid = release.tenantId().equals(index.tenantId())
                && release.workspaceId().equals(index.workspaceId())
                && release.tenantId().equals(policy.tenantId())
                && release.workspaceId().equals(policy.workspaceId())
                && indexReference.equals(index.reference())
                && policyReference.equals(policy.reference())
                && index.status() == KnowledgeIndexVersion.Status.READY;
        if (!valid) {
            throw new IllegalArgumentException(
                    "APVERO_RELEASE_KNOWLEDGE_BINDING_INVALID");
        }
    }

    private void requireResolvedResult(
            KnowledgeIndexVersion index,
            RetrievalPolicyVersion policy,
            KnowledgeRuntimeRetrievalResult result) {
        if (!indexesMatch(index, result)
                || !policy.id().equals(result.retrieval().retrievalPolicyVersionId())
                || !policies.supportsExecution(policy)) {
            throw new IllegalArgumentException(
                    "APVERO_RUNTIME_RETRIEVAL_RESULT_INVALID");
        }
    }

    private static boolean indexesMatch(
            KnowledgeIndexVersion index,
            KnowledgeRuntimeRetrievalResult result) {
        return index.id().equals(result.retrieval().indexVersionId());
    }

    private static String requiredReference(JsonNode binding, String name) {
        JsonNode value = binding.get(name);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException(
                    "APVERO_RELEASE_KNOWLEDGE_BINDING_INVALID");
        }
        return value.stringValue();
    }

    private static String query(JsonNode input) {
        JsonNode message = input == null ? null : input.get("message");
        if (message == null || !message.isString() || message.stringValue().isBlank()) {
            throw new IllegalArgumentException(
                    "APVERO_RUNTIME_RETRIEVAL_QUERY_INVALID");
        }
        return message.stringValue();
    }

    private long estimateInputUnits(JsonNode input, GroundingContext context) {
        long inputBytes = input.toString().getBytes(StandardCharsets.UTF_8).length;
        long totalBytes = Math.addExact(
                inputBytes,
                context.evidence().toString()
                        .getBytes(StandardCharsets.UTF_8).length);
        return Math.max(1, Math.addExact(totalBytes, 3L) / 4L);
    }

    private ObjectNode emptyOutput() {
        return json.createObjectNode();
    }

    private ProviderResult zeroResult() {
        return new ProviderResult(emptyOutput(), 0, 0, 0L);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(
                Math.max(0, System.nanoTime() - startedAtNanos));
    }
}
