package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingExecutionQuote;
import io.apvero.platform.capability.EmbeddingExecutionResult;
import io.apvero.platform.capability.EmbeddingNormalization;
import io.apvero.platform.capability.EmbeddingReplayPolicy;
import io.apvero.platform.capability.EmbeddingRouteProfile;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.capability.EmbeddingUsageQuality;
import io.apvero.platform.capability.EmbeddingVectorOutput;
import io.apvero.platform.capability.ModelRouteCapability;
import io.apvero.platform.capability.ModelRouteStatus;
import io.apvero.platform.governance.ExecutionComponentSnapshot;
import io.apvero.platform.governance.ExecutionComponentState;
import io.apvero.platform.governance.ExecutionComponentType;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingBatchPlan.PlannedChunk;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingLeaseCoordinator.AdmittedComponent;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeIndexBuildEmbeddingOrchestratorTest {
    private final KnowledgeEmbeddingBatchExecutor batches =
            mock(KnowledgeEmbeddingBatchExecutor.class);
    private final KnowledgeEmbeddingLeaseCoordinator coordinator =
            mock(KnowledgeEmbeddingLeaseCoordinator.class);
    private final KnowledgeIndexBuildTransitionKernel kernel =
            mock(KnowledgeIndexBuildTransitionKernel.class);
    private final EmbeddingCapability embeddings = mock(EmbeddingCapability.class);
    private final WorkspaceScope scope =
            new WorkspaceScope(UUID.randomUUID(), UUID.randomUUID());
    private final UUID routeId = UUID.randomUUID();
    private final UUID modelId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final BuildRow claim = build();
    private KnowledgeIndexBuildEmbeddingOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new KnowledgeIndexBuildEmbeddingOrchestrator(
                batches, coordinator, kernel, embeddings);
    }

    @Test
    void completeCursorAdvancesWithoutAdmissionOrProviderCall() {
        BuildRow indexing = withStatus(BuildStatus.INDEXING, BuildStep.INDEXING);
        when(batches.prepareNext(scope, claim, "worker")).thenReturn(Optional.empty());
        when(kernel.advanceToIndexingAndRelease(scope, claim, "worker"))
                .thenReturn(indexing);

        KnowledgeEmbeddingClaimOutcome outcome =
                orchestrator.executeClaim(scope, claim, "worker");

        assertThat(outcome.action()).isEqualTo(RecoveryAction.COMPLETE);
        assertThat(outcome.providerInvoked()).isFalse();
        verify(embeddings, never()).embed(org.mockito.ArgumentMatchers.any());
        verify(coordinator, never()).admitAndInspect(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unsafeDispatchedBatchRequiresReconciliationWithoutReplay() {
        KnowledgeEmbeddingBatchPlan plan = plan(
                KnowledgeEmbeddingBatchState.MISSING,
                EmbeddingReplayPolicy.RECONCILIATION_REQUIRED);
        AdmittedComponent component =
                component(plan, ExecutionComponentState.DISPATCHED);
        BuildRow failed = withStatus(BuildStatus.FAILED, BuildStep.EMBEDDING);
        when(batches.prepareNext(scope, claim, "worker")).thenReturn(Optional.of(plan));
        when(coordinator.admitAndInspect(scope, claim, "worker", plan))
                .thenReturn(component);
        when(coordinator.requireReconciliationAndFail(
                scope, claim, "worker", component, plan)).thenReturn(failed);

        KnowledgeEmbeddingClaimOutcome outcome =
                orchestrator.executeClaim(scope, claim, "worker");

        assertThat(outcome.action()).isEqualTo(RecoveryAction.RECONCILE);
        assertThat(outcome.providerInvoked()).isFalse();
        assertThat(outcome.build()).isEqualTo(failed);
        verify(embeddings, never()).embed(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void equalEntriesSettleAndAdvanceWithoutProviderCall() {
        KnowledgeEmbeddingBatchPlan plan = plan(
                KnowledgeEmbeddingBatchState.COMPLETE_EQUAL,
                EmbeddingReplayPolicy.SAFE_REPLAY);
        AdmittedComponent component =
                component(plan, ExecutionComponentState.DISPATCHED);
        BuildRow progressed = withProgress(1);
        when(batches.prepareNext(scope, claim, "worker")).thenReturn(Optional.of(plan));
        when(coordinator.admitAndInspect(scope, claim, "worker", plan))
                .thenReturn(component);
        when(kernel.recordEmbeddingProgressAndRelease(
                scope, claim, "worker", 1, 0)).thenReturn(progressed);

        KnowledgeEmbeddingClaimOutcome outcome =
                orchestrator.executeClaim(scope, claim, "worker");

        assertThat(outcome.action()).isEqualTo(RecoveryAction.SETTLE_ONLY);
        assertThat(outcome.providerInvoked()).isFalse();
        verify(coordinator).settle(
                scope,
                claim,
                "worker",
                component,
                plan,
                plan.estimatedInputUnits(),
                plan.quote().estimatedCostMicros(),
                io.apvero.platform.governance.ExecutionUsageQuality.ESTIMATED);
        verify(embeddings, never()).embed(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reservedBatchDispatchesAndInvokesProviderExactlyOnce() {
        KnowledgeEmbeddingBatchPlan plan = plan(
                KnowledgeEmbeddingBatchState.MISSING,
                EmbeddingReplayPolicy.SAFE_REPLAY);
        AdmittedComponent component =
                component(plan, ExecutionComponentState.RESERVED);
        EmbeddingExecutionResult result = result(plan);
        BuildRow progressed = withProgress(1);
        when(batches.prepareNext(scope, claim, "worker")).thenReturn(Optional.of(plan));
        when(coordinator.admitAndInspect(scope, claim, "worker", plan))
                .thenReturn(component);
        when(embeddings.embed(plan.executionRequest())).thenReturn(result);
        when(kernel.recordEmbeddingProgressAndRelease(
                scope, claim, "worker", 1, 0)).thenReturn(progressed);

        KnowledgeEmbeddingClaimOutcome outcome =
                orchestrator.executeClaim(scope, claim, "worker");

        assertThat(outcome.action()).isEqualTo(RecoveryAction.DISPATCH);
        assertThat(outcome.providerInvoked()).isTrue();
        verify(embeddings).embed(plan.executionRequest());
        verify(coordinator).markDispatched(scope, claim, "worker", component, plan);
        verify(coordinator).persist(scope, claim, "worker", plan, result);
    }

    private KnowledgeEmbeddingBatchPlan plan(
            KnowledgeEmbeddingBatchState state,
            EmbeddingReplayPolicy replayPolicy) {
        EmbeddingRouteSnapshot route = new EmbeddingRouteSnapshot(
                routeId,
                scope.tenantId(),
                scope.workspaceId(),
                "embedding-route",
                1,
                modelId,
                ModelRouteCapability.EMBEDDING,
                ModelRouteStatus.PUBLISHED,
                30_000,
                new EmbeddingRouteProfile(3, 8_192, 64, EmbeddingNormalization.L2),
                true,
                "READY",
                now());
        return new KnowledgeEmbeddingBatchPlan(
                scope,
                claim,
                0,
                "knowledge-embedding:" + "a".repeat(64),
                5,
                new EmbeddingExecutionQuote(route, 5, 2, "USD", replayPolicy),
                state,
                List.of(new PlannedChunk(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        chunkId,
                        0,
                        "input",
                        "sha256:" + "b".repeat(64))));
    }

    private AdmittedComponent component(
            KnowledgeEmbeddingBatchPlan plan,
            ExecutionComponentState state) {
        return new AdmittedComponent(
                reservationId,
                new ExecutionComponentSnapshot(
                        reservationId,
                        ExecutionComponentType.EMBEDDING_INDEX,
                        routeId,
                        "embedding-route@1",
                        plan.idempotencyIdentity(),
                        5,
                        null,
                        2,
                        null,
                        "USD",
                        null,
                        state,
                        null,
                        null));
    }

    private EmbeddingExecutionResult result(KnowledgeEmbeddingBatchPlan plan) {
        return new EmbeddingExecutionResult(
                routeId,
                "embedding-route@1",
                modelId,
                "embedding-model",
                3,
                plan.idempotencyIdentity(),
                List.of(new EmbeddingVectorOutput(
                        chunkId, "b".repeat(64), List.of(1f, 0f, 0f))),
                5L,
                EmbeddingUsageQuality.ACTUAL,
                2,
                "USD",
                "provider-request",
                1);
    }

    private BuildRow build() {
        return new BuildRow(
                UUID.randomUUID(),
                scope.tenantId(),
                scope.workspaceId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "1.0.0",
                routeId,
                "embedding-route@1",
                3,
                8_192,
                64,
                "L2",
                "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64),
                1,
                1,
                BuildStatus.EMBEDDING,
                BuildStep.EMBEDDING,
                1,
                3,
                false,
                null,
                "worker",
                now().plusMinutes(1),
                2,
                false,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "{}",
                now(),
                null,
                now(),
                now());
    }

    private BuildRow withStatus(BuildStatus status, BuildStep step) {
        return copy(status, step, claim.embeddedEntryCount());
    }

    private BuildRow withProgress(int progress) {
        return copy(BuildStatus.EMBEDDING, BuildStep.EMBEDDING, progress);
    }

    private BuildRow copy(BuildStatus status, BuildStep step, int progress) {
        return new BuildRow(
                claim.id(), claim.tenantId(), claim.workspaceId(), claim.knowledgeIndexId(),
                claim.knowledgeBaseId(), claim.requestedVersion(), claim.embeddingRouteId(),
                claim.embeddingRouteReference(), claim.vectorDimension(),
                claim.maximumInputTokens(), claim.maximumBatchSize(), claim.normalization(),
                claim.requestDigest(), claim.sourceSetDigest(), claim.requestedSourceCount(),
                claim.requestedChunkCount(), status, step, claim.attemptCount(),
                claim.maximumAttempts(), claim.retryable(), claim.nextAttemptAt(), null, null,
                claim.lockVersion() + 1, claim.cancellationRequested(), progress,
                claim.validatedEntryCount(), progress == 0 ? null : progress - 1,
                claim.validationDigest(), claim.artifactDigest(), claim.publishedVersionId(),
                claim.errorCode(), claim.errorCategory(), claim.reconciliationRequired(),
                claim.failureMetadataJson(), claim.startedAt(), claim.completedAt(),
                claim.createdAt(), now());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
