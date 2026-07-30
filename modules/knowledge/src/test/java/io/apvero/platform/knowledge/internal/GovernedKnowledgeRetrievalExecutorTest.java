package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingExecutionQuote;
import io.apvero.platform.capability.EmbeddingExecutionRequest;
import io.apvero.platform.capability.EmbeddingExecutionResult;
import io.apvero.platform.capability.EmbeddingInputUnitEstimator;
import io.apvero.platform.capability.EmbeddingNormalization;
import io.apvero.platform.capability.EmbeddingReplayPolicy;
import io.apvero.platform.capability.EmbeddingRouteProfile;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.capability.EmbeddingUsageQuality;
import io.apvero.platform.capability.EmbeddingVectorOutput;
import io.apvero.platform.capability.ModelRouteCapability;
import io.apvero.platform.capability.ModelRouteStatus;
import io.apvero.platform.governance.BudgetExceededException;
import io.apvero.platform.governance.ExecutionAdmission;
import io.apvero.platform.governance.ExecutionComponentReconciliation;
import io.apvero.platform.governance.ExecutionComponentSettlement;
import io.apvero.platform.governance.ExecutionComponentSnapshot;
import io.apvero.platform.governance.ExecutionComponentState;
import io.apvero.platform.governance.ExecutionComponentType;
import io.apvero.platform.governance.ExecutionGovernance;
import io.apvero.platform.governance.ExecutionReservationRequest;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class GovernedKnowledgeRetrievalExecutorTest {
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 30, 12, 0, 0, 0, ZoneOffset.UTC);

    private final KnowledgeAvailability availability = mock(KnowledgeAvailability.class);
    private final WorkspaceScopeCatalog workspaces = mock(WorkspaceScopeCatalog.class);
    private final KnowledgeIndexPersistenceRepository indexes =
            mock(KnowledgeIndexPersistenceRepository.class);
    private final EmbeddingCapability embeddings = mock(EmbeddingCapability.class);
    private final EmbeddingInputUnitEstimator estimator = mock(EmbeddingInputUnitEstimator.class);
    private final ExecutionGovernance governance = mock(ExecutionGovernance.class);
    private final ExactKnowledgeRetrievalKernel retrieval =
            mock(ExactKnowledgeRetrievalKernel.class);
    private final GovernedKnowledgeRetrievalExecutor executor =
            new GovernedKnowledgeRetrievalExecutor(
                    availability, workspaces, indexes, embeddings, estimator, governance, retrieval);

    private final WorkspaceScope scope =
            new WorkspaceScope(UUID.randomUUID(), UUID.randomUUID());
    private final UUID indexVersionId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final UUID routeId = UUID.randomUUID();
    private final UUID buildId = UUID.randomUUID();
    private final UUID indexId = UUID.randomUUID();
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private final UUID modelId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final KnowledgeCommandContext context =
            new KnowledgeCommandContext("developer", "127.0.0.1", "trace-query-1");

    @BeforeEach
    void setUp() {
        when(workspaces.require(scope.workspaceId())).thenReturn(scope);
        when(indexes.findVersion(scope, indexVersionId)).thenReturn(Optional.of(version()));
        when(indexes.findPolicy(scope, policyId)).thenReturn(Optional.of(policy()));
        when(indexes.findBuild(scope, buildId)).thenReturn(Optional.of(build()));
        when(embeddings.resolveEmbeddingRoute(scope.workspaceId(), routeId)).thenReturn(route());
        when(estimator.estimateUnits("find evidence")).thenReturn(13L);
        when(embeddings.quote(scope.workspaceId(), routeId, 13L)).thenReturn(quote(
                EmbeddingReplayPolicy.RECONCILIATION_REQUIRED));
        when(governance.admit(any(ExecutionReservationRequest.class)))
                .thenReturn(new ExecutionAdmission(reservationId, true, false));
    }

    @Test
    void executesOneGovernedEmbeddingThenRanksWithoutRetainingRawQuery() {
        when(governance.findComponent(
                        scope.workspaceId(), reservationId, expectedIdentity()))
                .thenReturn(Optional.of(component(
                        ExecutionComponentState.RESERVED, null)));
        when(embeddings.embed(any(EmbeddingExecutionRequest.class)))
                .thenAnswer(invocation -> result(invocation.getArgument(0), null));
        when(retrieval.retrieve(
                        scope, indexVersionId, List.of(1.0F, 0.0F, 0.0F), 0.7, 5))
                .thenReturn(List.of());

        GovernedRetrievalExecution execution = executor.execute(
                scope.workspaceId(), context, indexVersionId, policyId, "  find evidence  ");

        assertThat(execution.indexVersion().id()).isEqualTo(indexVersionId);
        assertThat(execution.retrievalPolicy().id()).isEqualTo(policyId);
        assertThat(execution.queryDigest()).matches("^sha256:[a-f0-9]{64}$");

        ArgumentCaptor<ExecutionReservationRequest> reservation =
                ArgumentCaptor.forClass(ExecutionReservationRequest.class);
        ArgumentCaptor<EmbeddingExecutionRequest> provider =
                ArgumentCaptor.forClass(EmbeddingExecutionRequest.class);
        ArgumentCaptor<ExecutionComponentSettlement> settlement =
                ArgumentCaptor.forClass(ExecutionComponentSettlement.class);
        verify(governance).admit(reservation.capture());
        verify(embeddings).embed(provider.capture());
        verify(governance).settle(settlement.capture());

        assertThat(reservation.getValue().subject().type().name()).isEqualTo("KNOWLEDGE_QUERY");
        assertThat(reservation.getValue().components().getFirst().type())
                .isEqualTo(ExecutionComponentType.EMBEDDING_QUERY);
        assertThat(provider.getValue().orderedInputs().getFirst().boundedText())
                .isEqualTo("find evidence");
        assertThat(provider.getValue().orderedInputs().getFirst().contentDigest())
                .isEqualTo(execution.queryDigest().substring("sha256:".length()));
        assertThat(settlement.getValue().succeeded()).isTrue();
        assertThat(settlement.getValue().actualUnits()).isEqualTo(13L);

        InOrder order = inOrder(embeddings, governance, retrieval);
        order.verify(embeddings).resolveEmbeddingRoute(scope.workspaceId(), routeId);
        order.verify(embeddings).quote(scope.workspaceId(), routeId, 13L);
        order.verify(governance).admit(any(ExecutionReservationRequest.class));
        order.verify(governance).findComponent(
                scope.workspaceId(), reservationId, expectedIdentity());
        order.verify(governance).markDispatched(any());
        order.verify(embeddings).embed(any());
        order.verify(governance).settle(any(ExecutionComponentSettlement.class));
        order.verify(retrieval).retrieve(
                scope, indexVersionId, List.of(1.0F, 0.0F, 0.0F), 0.7, 5);
    }

    @Test
    void admissionDenialOccursBeforeDispatchAndProviderCall() {
        when(governance.admit(any(ExecutionReservationRequest.class)))
                .thenThrow(new BudgetExceededException());

        assertThatThrownBy(() -> executor.execute(
                        scope.workspaceId(), context, indexVersionId, policyId, "find evidence"))
                .isInstanceOf(BudgetExceededException.class);

        verify(governance, never()).markDispatched(any());
        verify(embeddings, never()).embed(any());
        verify(retrieval, never()).retrieve(any(), any(), any(), anyDouble(), anyInt());
    }

    @Test
    void ambiguousProviderOutcomeRequiresReconciliationAndIsNeverRetried() {
        when(governance.findComponent(
                        scope.workspaceId(), reservationId, expectedIdentity()))
                .thenReturn(Optional.of(component(
                        ExecutionComponentState.RESERVED, null)));
        when(embeddings.embed(any()))
                .thenThrow(new IllegalStateException("APVERO_EMBEDDING_PROVIDER_TIMEOUT"));

        assertCode(
                () -> executor.execute(
                        scope.workspaceId(), context, indexVersionId, policyId, "find evidence"),
                "APVERO_EMBEDDING_OUTCOME_AMBIGUOUS");

        verify(governance).requireReconciliation(any(ExecutionComponentReconciliation.class));
        verify(governance, never()).settle(any(ExecutionComponentSettlement.class));
        verify(embeddings).embed(any());
        verify(retrieval, never()).retrieve(any(), any(), any(), anyDouble(), anyInt());
    }

    @Test
    void knownFailureSettlesExactlyOnceWithEstimatedUsage() {
        when(embeddings.quote(scope.workspaceId(), routeId, 13L))
                .thenReturn(quote(EmbeddingReplayPolicy.SAFE_REPLAY));
        when(governance.findComponent(
                        scope.workspaceId(), reservationId, expectedIdentity()))
                .thenReturn(Optional.of(component(
                        ExecutionComponentState.RESERVED, null)));
        when(embeddings.embed(any()))
                .thenThrow(new IllegalStateException("APVERO_EMBEDDING_PROVIDER_TIMEOUT"));

        assertCode(
                () -> executor.execute(
                        scope.workspaceId(), context, indexVersionId, policyId, "find evidence"),
                "APVERO_EMBEDDING_PROVIDER_TIMEOUT");

        ArgumentCaptor<ExecutionComponentSettlement> settlement =
                ArgumentCaptor.forClass(ExecutionComponentSettlement.class);
        verify(governance).settle(settlement.capture());
        assertThat(settlement.getValue().succeeded()).isFalse();
        assertThat(settlement.getValue().actualUnits()).isEqualTo(13L);
        assertThat(settlement.getValue().failureCode())
                .isEqualTo("APVERO_EMBEDDING_PROVIDER_TIMEOUT");
        verify(embeddings).embed(any());
    }

    @Test
    void preexistingDispatchedComponentBecomesReconciliationWithoutProviderReplay() {
        when(governance.findComponent(
                        scope.workspaceId(), reservationId, expectedIdentity()))
                .thenReturn(Optional.of(component(
                        ExecutionComponentState.DISPATCHED, null)));

        assertCode(
                () -> executor.execute(
                        scope.workspaceId(), context, indexVersionId, policyId, "find evidence"),
                "APVERO_EMBEDDING_OUTCOME_AMBIGUOUS");

        verify(governance).requireReconciliation(any(ExecutionComponentReconciliation.class));
        verify(embeddings, never()).embed(any());
    }

    @Test
    void providerIdentityLedgerFailureIsSafeAndNeverReplaysProvider() {
        when(governance.findComponent(
                        scope.workspaceId(), reservationId, expectedIdentity()))
                .thenReturn(Optional.of(component(
                        ExecutionComponentState.RESERVED, null)));
        when(embeddings.embed(any(EmbeddingExecutionRequest.class)))
                .thenAnswer(invocation -> result(invocation.getArgument(0), "provider-request-1"));
        org.mockito.Mockito.doNothing()
                .doThrow(new IllegalStateException("database unavailable"))
                .when(governance)
                .markDispatched(any());

        assertCode(
                () -> executor.execute(
                        scope.workspaceId(), context, indexVersionId, policyId, "find evidence"),
                "APVERO_KNOWLEDGE_QUERY_SETTLEMENT_CONFLICT");

        verify(embeddings, times(1)).embed(any());
        verify(governance, never()).settle(any(ExecutionComponentSettlement.class));
        verify(retrieval, never()).retrieve(any(), any(), any(), anyDouble(), anyInt());
    }

    private String expectedIdentity() {
        KnowledgeCanonicalDigests.DigestBuilder digest =
                KnowledgeCanonicalDigests.builder("apvero-knowledge-query-v1");
        digest.addUuid(scope.tenantId());
        digest.addUuid(scope.workspaceId());
        digest.addUuid(indexVersionId);
        digest.addUuid(policyId);
        digest.addString(context.traceId());
        digest.addString(KnowledgeCanonicalDigests.text("find evidence"));
        return "knowledge-query:" + digest.finish().substring("sha256:".length());
    }

    private VersionRow version() {
        return new VersionRow(
                indexVersionId, scope.tenantId(), scope.workspaceId(), indexId, buildId,
                "1.0.0", "index@1.0.0", routeId, "route@1", 3, 1, 1,
                digest('a'), "READY", NOW);
    }

    private BuildRow build() {
        return new BuildRow(
                buildId, scope.tenantId(), scope.workspaceId(), indexId,
                knowledgeBaseId, "1.0.0", routeId, "route@1", 3, 8192, 64, "L2",
                digest('b'), digest('c'), 1, 1, BuildStatus.READY, BuildStep.COMPLETE,
                1, 3, false, null, null, null, 4, false, 1, 1, 0,
                digest('d'), digest('a'), indexVersionId, null, null, false,
                "{}", NOW, NOW, NOW, NOW);
    }

    private RetrievalPolicyRow policy() {
        return new RetrievalPolicyRow(
                policyId, scope.tenantId(), scope.workspaceId(), "default", "1.0.0",
                "exact-cosine@1.0.0", "apvero-utf8-byte@1.0.0", 1, 5, 4096,
                new BigDecimal("0.700000"), "KEEP", "NO_EVIDENCE",
                digest('e'), "developer", NOW);
    }

    private EmbeddingRouteSnapshot route() {
        return new EmbeddingRouteSnapshot(
                routeId, scope.tenantId(), scope.workspaceId(), "route", 1,
                modelId, ModelRouteCapability.EMBEDDING, ModelRouteStatus.PUBLISHED,
                30_000, new EmbeddingRouteProfile(
                        3, 8192, 64, EmbeddingNormalization.L2),
                true, "READY", NOW);
    }

    private EmbeddingExecutionQuote quote(EmbeddingReplayPolicy replayPolicy) {
        return new EmbeddingExecutionQuote(route(), 13, 0, "USD", replayPolicy);
    }

    private EmbeddingExecutionResult result(
            EmbeddingExecutionRequest request,
            String providerRequestIdentity) {
        return new EmbeddingExecutionResult(
                routeId, "route@1", route().modelId(), "model", 3,
                request.executionIdentity(),
                List.of(new EmbeddingVectorOutput(
                        request.orderedInputs().getFirst().itemId(),
                        request.orderedInputs().getFirst().contentDigest(),
                        List.of(1.0F, 0.0F, 0.0F))),
                null, EmbeddingUsageQuality.UNAVAILABLE, 0, "USD",
                providerRequestIdentity, 4);
    }

    private ExecutionComponentSnapshot component(
            ExecutionComponentState state,
            String failureCode) {
        boolean settled = state == ExecutionComponentState.SUCCEEDED
                || state == ExecutionComponentState.FAILED;
        return new ExecutionComponentSnapshot(
                reservationId, ExecutionComponentType.EMBEDDING_QUERY, routeId, "route@1",
                expectedIdentity(), 13, settled ? 13L : null, 0, settled ? 0L : null,
                "USD", settled ? io.apvero.platform.governance.ExecutionUsageQuality.ESTIMATED : null,
                state, null, failureCode);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(KnowledgeException.class)
                .satisfies(error -> assertThat(((KnowledgeException) error).code()).isEqualTo(code));
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
