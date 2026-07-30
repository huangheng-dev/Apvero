package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingExecutionQuote;
import io.apvero.platform.capability.EmbeddingExecutionRequest;
import io.apvero.platform.capability.EmbeddingExecutionResult;
import io.apvero.platform.capability.EmbeddingInput;
import io.apvero.platform.capability.EmbeddingInputUnitEstimator;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.capability.EmbeddingUsageQuality;
import io.apvero.platform.governance.ExecutionAdmission;
import io.apvero.platform.governance.ExecutionComponentDispatch;
import io.apvero.platform.governance.ExecutionComponentReconciliation;
import io.apvero.platform.governance.ExecutionComponentRequest;
import io.apvero.platform.governance.ExecutionComponentSettlement;
import io.apvero.platform.governance.ExecutionComponentSnapshot;
import io.apvero.platform.governance.ExecutionComponentState;
import io.apvero.platform.governance.ExecutionComponentType;
import io.apvero.platform.governance.ExecutionGovernance;
import io.apvero.platform.governance.ExecutionReservationRequest;
import io.apvero.platform.governance.ExecutionSubject;
import io.apvero.platform.governance.ExecutionUsageQuality;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class GovernedKnowledgeRetrievalExecutor {
    private static final int MAXIMUM_QUERY_CODE_POINTS = 20_000;

    private final KnowledgeAvailability availability;
    private final WorkspaceScopeCatalog workspaces;
    private final KnowledgeIndexPersistenceRepository indexes;
    private final EmbeddingCapability embeddings;
    private final EmbeddingInputUnitEstimator estimator;
    private final ExecutionGovernance governance;
    private final ExactKnowledgeRetrievalKernel retrieval;

    GovernedKnowledgeRetrievalExecutor(
            KnowledgeAvailability availability,
            WorkspaceScopeCatalog workspaces,
            KnowledgeIndexPersistenceRepository indexes,
            EmbeddingCapability embeddings,
            EmbeddingInputUnitEstimator estimator,
            ExecutionGovernance governance,
            ExactKnowledgeRetrievalKernel retrieval) {
        this.availability = availability;
        this.workspaces = workspaces;
        this.indexes = indexes;
        this.embeddings = embeddings;
        this.estimator = estimator;
        this.governance = governance;
        this.retrieval = retrieval;
    }

    GovernedRetrievalExecution execute(
            UUID workspaceId,
            KnowledgeCommandContext context,
            UUID indexVersionId,
            UUID retrievalPolicyVersionId,
            String query) {
        availability.requireEnabled();
        if (workspaceId == null
                || indexVersionId == null
                || retrievalPolicyVersionId == null) {
            throw problem(
                    "APVERO_KNOWLEDGE_IDENTIFIER_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        WorkspaceScope scope = workspaces.require(workspaceId);
        QueryInput input = queryInput(context, query);
        VersionRow version = indexes.findVersion(scope, indexVersionId)
                .filter(row -> "READY".equals(row.status()))
                .orElseThrow(() -> notFound("APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND"));
        RetrievalPolicyRow policy = indexes.findPolicy(scope, retrievalPolicyVersionId)
                .orElseThrow(() -> notFound(
                        "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_VERSION_NOT_FOUND"));
        BuildRow build = indexes.findBuild(scope, version.knowledgeIndexBuildId())
                .filter(row -> row.status()
                        == KnowledgeIndexPersistenceRecords.BuildStatus.READY)
                .orElseThrow(() -> notFound("APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND"));
        requirePinnedVersion(version, build);

        EmbeddingRouteSnapshot route =
                embeddings.resolveEmbeddingRoute(scope.workspaceId(), version.embeddingRouteId());
        requirePinnedRoute(scope, version, build, route);
        long estimatedUnits = estimator.estimateUnits(input.query());
        if (estimatedUnits < 1 || estimatedUnits > route.profile().maximumInputTokens()) {
            throw problem(
                    "APVERO_KNOWLEDGE_RETRIEVAL_QUERY_INPUT_LIMIT_EXCEEDED",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        EmbeddingExecutionQuote quote =
                embeddings.quote(scope.workspaceId(), version.embeddingRouteId(), estimatedUnits);
        requireQuote(route, quote, estimatedUnits);

        String identity = executionIdentity(
                scope, input.context(), version.id(), policy.id(), input.queryDigest());
        UUID queryId = KnowledgeCanonicalDigests.stableId(
                "apvero:knowledge-query-subject:" + identity);
        ExecutionAdmission admission = governance.admit(new ExecutionReservationRequest(
                scope.workspaceId(),
                ExecutionSubject.knowledgeQuery(queryId),
                input.context().actorId(),
                input.context().traceId(),
                List.of(new ExecutionComponentRequest(
                        ExecutionComponentType.EMBEDDING_QUERY,
                        version.embeddingRouteId(),
                        version.embeddingRouteReference(),
                        identity,
                        estimatedUnits,
                        quote.estimatedCostMicros(),
                        quote.currency()))));
        ExecutionComponentSnapshot component = governance.findComponent(
                        scope.workspaceId(), admission.reservationId(), identity)
                .orElseThrow(() -> problem(
                        "APVERO_EXECUTION_COMPONENT_NOT_FOUND",
                        KnowledgeException.Category.CONFLICT));
        requireComponent(version, quote, identity, component);
        requireReplaySafeState(admission, component, identity);

        governance.markDispatched(
                new ExecutionComponentDispatch(admission.reservationId(), identity, null));
        UUID itemId = KnowledgeCanonicalDigests.stableId(
                "apvero:knowledge-query-input:" + identity);
        EmbeddingExecutionRequest request = new EmbeddingExecutionRequest(
                scope.workspaceId(),
                version.embeddingRouteReference(),
                identity,
                List.of(new EmbeddingInput(
                        itemId,
                        input.queryDigest().substring("sha256:".length()),
                        input.query())));

        EmbeddingExecutionResult result;
        try {
            result = embeddings.embed(request);
            requireResult(version, quote, request, result);
        } catch (RuntimeException failure) {
            throw settleProviderFailure(admission, identity, quote, estimatedUnits, failure);
        }

        if (result.providerRequestIdentity() != null) {
            try {
                governance.markDispatched(new ExecutionComponentDispatch(
                        admission.reservationId(), identity, result.providerRequestIdentity()));
            } catch (RuntimeException failure) {
                throw settlementConflict();
            }
        }
        settleSuccess(admission, identity, quote, estimatedUnits, result);
        List<KnowledgeIndexPersistenceRecords.ExactRetrievalCandidate> candidates =
                retrieval.retrieve(
                        scope,
                        version.id(),
                        result.orderedOutputs().getFirst().vector(),
                        policy.minimumScore().doubleValue(),
                        policy.topK());
        return new GovernedRetrievalExecution(
                version, policy, input.queryDigest(), candidates, result.latencyMillis());
    }

    private RuntimeException settleProviderFailure(
            ExecutionAdmission admission,
            String identity,
            EmbeddingExecutionQuote quote,
            long estimatedUnits,
            RuntimeException failure) {
        KnowledgeIndexBuildFailure normalized =
                KnowledgeEmbeddingFailureNormalizer.normalize(failure, quote.replayPolicy());
        if (normalized.reconciliationRequired()) {
            try {
                governance.requireReconciliation(new ExecutionComponentReconciliation(
                        admission.reservationId(),
                        identity,
                        "APVERO_EMBEDDING_OUTCOME_AMBIGUOUS"));
            } catch (RuntimeException ledgerFailure) {
                return settlementConflict();
            }
            return problem(
                    "APVERO_EMBEDDING_OUTCOME_AMBIGUOUS",
                    KnowledgeException.Category.CONFLICT);
        }
        try {
            governance.settle(new ExecutionComponentSettlement(
                    admission.reservationId(),
                    identity,
                    estimatedUnits,
                    quote.estimatedCostMicros(),
                    quote.currency(),
                    ExecutionUsageQuality.ESTIMATED,
                    false,
                    normalized.code()));
        } catch (RuntimeException ledgerFailure) {
            return settlementConflict();
        }
        return problem(normalized.code(), KnowledgeException.Category.UNPROCESSABLE);
    }

    private void settleSuccess(
            ExecutionAdmission admission,
            String identity,
            EmbeddingExecutionQuote quote,
            long estimatedUnits,
            EmbeddingExecutionResult result) {
        long units = result.actualInputUnits() == null
                ? estimatedUnits
                : result.actualInputUnits();
        ExecutionUsageQuality quality = result.usageQuality() == EmbeddingUsageQuality.ACTUAL
                ? ExecutionUsageQuality.ACTUAL
                : ExecutionUsageQuality.ESTIMATED;
        try {
            governance.settle(new ExecutionComponentSettlement(
                    admission.reservationId(),
                    identity,
                    units,
                    result.costMicros(),
                    quote.currency(),
                    quality,
                    true,
                    null));
        } catch (RuntimeException failure) {
            throw settlementConflict();
        }
    }

    private void requireReplaySafeState(
            ExecutionAdmission admission,
            ExecutionComponentSnapshot component,
            String identity) {
        switch (component.state()) {
            case RESERVED -> {
                return;
            }
            case DISPATCHED -> {
                try {
                    governance.requireReconciliation(new ExecutionComponentReconciliation(
                            admission.reservationId(),
                            identity,
                            "APVERO_EMBEDDING_OUTCOME_AMBIGUOUS"));
                } catch (RuntimeException failure) {
                    throw settlementConflict();
                }
                throw problem(
                        "APVERO_EMBEDDING_OUTCOME_AMBIGUOUS",
                        KnowledgeException.Category.CONFLICT);
            }
            case RECONCILIATION_REQUIRED -> throw problem(
                    "APVERO_EMBEDDING_OUTCOME_AMBIGUOUS",
                    KnowledgeException.Category.CONFLICT);
            case SUCCEEDED -> throw problem(
                    "APVERO_KNOWLEDGE_QUERY_ALREADY_SETTLED",
                    KnowledgeException.Category.CONFLICT);
            case FAILED -> throw problem(
                    component.failureCode() == null
                            ? "APVERO_EMBEDDING_PROVIDER_FAILED"
                            : component.failureCode(),
                    KnowledgeException.Category.UNPROCESSABLE);
        }
    }

    private static QueryInput queryInput(KnowledgeCommandContext context, String query) {
        if (context == null) {
            throw problem(
                    "APVERO_KNOWLEDGE_REQUEST_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        String actor = bounded(context.actorId(), 160, "APVERO_KNOWLEDGE_ACTOR_INVALID");
        String trace = bounded(context.traceId(), 80, "APVERO_KNOWLEDGE_TRACE_INVALID");
        if (query == null) {
            throw problem(
                    "APVERO_KNOWLEDGE_RETRIEVAL_QUERY_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        String normalized = query.strip();
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length())
                        > MAXIMUM_QUERY_CODE_POINTS) {
            throw problem(
                    "APVERO_KNOWLEDGE_RETRIEVAL_QUERY_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        return new QueryInput(
                new KnowledgeCommandContext(actor, context.sourceIp(), trace),
                normalized,
                KnowledgeCanonicalDigests.bytes(
                        normalized.getBytes(StandardCharsets.UTF_8)));
    }

    private static String bounded(String value, int maximum, String code) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw problem(code, KnowledgeException.Category.BAD_REQUEST);
        }
        return value.trim();
    }

    private static String executionIdentity(
            WorkspaceScope scope,
            KnowledgeCommandContext context,
            UUID versionId,
            UUID policyId,
            String queryDigest) {
        KnowledgeCanonicalDigests.DigestBuilder digest =
                KnowledgeCanonicalDigests.builder("apvero-knowledge-query-v1");
        digest.addUuid(scope.tenantId());
        digest.addUuid(scope.workspaceId());
        digest.addUuid(versionId);
        digest.addUuid(policyId);
        digest.addString(context.traceId());
        digest.addString(queryDigest);
        return "knowledge-query:" + digest.finish().substring("sha256:".length());
    }

    private static void requirePinnedVersion(VersionRow version, BuildRow build) {
        if (!build.id().equals(version.knowledgeIndexBuildId())
                || !build.knowledgeIndexId().equals(version.knowledgeIndexId())
                || !build.embeddingRouteId().equals(version.embeddingRouteId())
                || !build.embeddingRouteReference().equals(version.embeddingRouteReference())
                || build.vectorDimension() != version.vectorDimension()) {
            throw problem(
                    "APVERO_KNOWLEDGE_INDEX_VERSION_INTEGRITY_INVALID",
                    KnowledgeException.Category.CONFLICT);
        }
    }

    private static void requirePinnedRoute(
            WorkspaceScope scope,
            VersionRow version,
            BuildRow build,
            EmbeddingRouteSnapshot route) {
        if (!route.tenantId().equals(scope.tenantId())
                || !route.workspaceId().equals(scope.workspaceId())
                || !route.id().equals(version.embeddingRouteId())
                || !route.reference().equals(version.embeddingRouteReference())
                || route.profile().dimension() != version.vectorDimension()
                || route.profile().maximumInputTokens() != build.maximumInputTokens()
                || route.profile().maximumBatchSize() != build.maximumBatchSize()
                || !route.profile().normalization().name().equals(build.normalization())
                || !route.availableForNewBuilds()) {
            throw problem(
                    "APVERO_KNOWLEDGE_INDEX_VERSION_ROUTE_INVALID",
                    KnowledgeException.Category.CONFLICT);
        }
    }

    private static void requireQuote(
            EmbeddingRouteSnapshot route,
            EmbeddingExecutionQuote quote,
            long estimatedUnits) {
        if (!quote.route().equals(route)
                || quote.estimatedInputUnits() != estimatedUnits) {
            throw problem(
                    "APVERO_KNOWLEDGE_QUERY_QUOTE_INVALID",
                    KnowledgeException.Category.CONFLICT);
        }
    }

    private static void requireComponent(
            VersionRow version,
            EmbeddingExecutionQuote quote,
            String identity,
            ExecutionComponentSnapshot component) {
        if (component.type() != ExecutionComponentType.EMBEDDING_QUERY
                || !component.modelRouteId().equals(version.embeddingRouteId())
                || !component.modelRouteReference().equals(version.embeddingRouteReference())
                || !component.idempotencyIdentity().equals(identity)
                || component.estimatedUnits() != quote.estimatedInputUnits()
                || component.estimatedCostMicros() != quote.estimatedCostMicros()
                || !component.currency().equals(quote.currency())) {
            throw problem(
                    "APVERO_EXECUTION_RESERVATION_IDEMPOTENCY_CONFLICT",
                    KnowledgeException.Category.CONFLICT);
        }
    }

    private static void requireResult(
            VersionRow version,
            EmbeddingExecutionQuote quote,
            EmbeddingExecutionRequest request,
            EmbeddingExecutionResult result) {
        result.validateAgainst(request);
        if (!result.routeId().equals(version.embeddingRouteId())
                || !result.routeReference().equals(version.embeddingRouteReference())
                || result.dimension() != version.vectorDimension()
                || !result.currency().equals(quote.currency())
                || result.orderedOutputs().size() != 1) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_RESULT_INVALID");
        }
    }

    private static KnowledgeException notFound(String code) {
        return problem(code, KnowledgeException.Category.NOT_FOUND);
    }

    private static KnowledgeException settlementConflict() {
        return problem(
                "APVERO_KNOWLEDGE_QUERY_SETTLEMENT_CONFLICT",
                KnowledgeException.Category.CONFLICT);
    }

    private static KnowledgeException problem(String code, KnowledgeException.Category category) {
        return new KnowledgeException(code, category);
    }

    private record QueryInput(
            KnowledgeCommandContext context,
            String query,
            String queryDigest) {}
}
