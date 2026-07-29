package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingExecutionResult;
import io.apvero.platform.capability.EmbeddingReplayPolicy;
import io.apvero.platform.capability.EmbeddingUsageQuality;
import io.apvero.platform.governance.ExecutionComponentState;
import io.apvero.platform.governance.ExecutionUsageQuality;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingLeaseCoordinator.AdmittedComponent;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.ComponentState;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.EntryState;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.EntryKindTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.OutcomeTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.QualityTag;
import java.util.Optional;

class KnowledgeIndexBuildEmbeddingOrchestrator {
    private final KnowledgeEmbeddingBatchExecutor batches;
    private final KnowledgeEmbeddingLeaseCoordinator coordinator;
    private final KnowledgeIndexBuildTransitionKernel kernel;
    private final EmbeddingCapability embeddings;
    private final KnowledgeIndexBuildTelemetry telemetry;

    KnowledgeIndexBuildEmbeddingOrchestrator(
            KnowledgeEmbeddingBatchExecutor batches,
            KnowledgeEmbeddingLeaseCoordinator coordinator,
            KnowledgeIndexBuildTransitionKernel kernel,
            EmbeddingCapability embeddings,
            KnowledgeIndexBuildTelemetry telemetry) {
        this.batches = batches;
        this.coordinator = coordinator;
        this.kernel = kernel;
        this.embeddings = embeddings;
        this.telemetry = telemetry;
    }

    KnowledgeEmbeddingClaimOutcome executeClaim(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner) {
        Optional<KnowledgeEmbeddingBatchPlan> prepared =
                batches.prepareNext(scope, claim, leaseOwner);
        if (prepared.isEmpty()) {
            BuildRow indexing =
                    kernel.advanceToIndexingAndRelease(scope, claim, leaseOwner);
            return new KnowledgeEmbeddingClaimOutcome(
                    indexing, RecoveryAction.COMPLETE, false);
        }
        KnowledgeEmbeddingBatchPlan plan = prepared.orElseThrow();
        AdmittedComponent component =
                coordinator.admitAndInspect(scope, claim, leaseOwner, plan);
        RecoveryAction action = KnowledgeEmbeddingRecoveryDecider.decide(
                componentState(component.snapshot().state()),
                plan.state() == KnowledgeEmbeddingBatchState.COMPLETE_EQUAL
                        ? EntryState.COMPLETE_EQUAL
                        : EntryState.NONE,
                plan.quote().replayPolicy());

        return switch (action) {
            case DISPATCH, REPLAY -> invoke(
                    scope, claim, leaseOwner, plan, component, action);
            case SETTLE_ONLY -> settleOnly(
                    scope, claim, leaseOwner, plan, component, action);
            case COMPLETE -> complete(
                    scope, claim, leaseOwner, plan, action);
            case RECONCILE -> new KnowledgeEmbeddingClaimOutcome(
                    coordinator.requireReconciliationAndFail(
                            scope, claim, leaseOwner, component, plan),
                    action,
                    false);
            case INTEGRITY_FAILURE -> fail(
                    scope,
                    claim,
                    leaseOwner,
                    action,
                    "APVERO_KNOWLEDGE_ENTRY_BATCH_INTEGRITY");
            case LEDGER_ARTIFACT_INCONSISTENCY -> fail(
                    scope,
                    claim,
                    leaseOwner,
                    action,
                    "APVERO_KNOWLEDGE_LEDGER_ARTIFACT_INCONSISTENT");
            case ADMIT -> throw new IllegalStateException(
                    "APVERO_EXECUTION_COMPONENT_NOT_FOUND");
        };
    }

    private KnowledgeEmbeddingClaimOutcome invoke(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            KnowledgeEmbeddingBatchPlan plan,
            AdmittedComponent component,
            RecoveryAction action) {
        coordinator.markDispatched(scope, claim, leaseOwner, component, plan);
        EmbeddingExecutionResult result;
        try {
            result = embeddings.embed(plan.executionRequest());
        } catch (RuntimeException exception) {
            KnowledgeIndexBuildFailure failure = KnowledgeEmbeddingFailureNormalizer.normalize(
                    exception, plan.quote().replayPolicy());
            if (failure.reconciliationRequired()) {
                return new KnowledgeEmbeddingClaimOutcome(
                        coordinator.requireReconciliationAndFail(
                                scope, claim, leaseOwner, component, plan),
                        RecoveryAction.RECONCILE,
                        true);
            }
            return fail(scope, claim, leaseOwner, action, failure, true);
        }
        coordinator.enrichProviderIdentity(
                scope,
                claim,
                leaseOwner,
                component,
                plan,
                result.providerRequestIdentity());
        coordinator.persist(scope, claim, leaseOwner, plan, result);
        long units = result.actualInputUnits() == null
                ? plan.estimatedInputUnits()
                : result.actualInputUnits();
        ExecutionUsageQuality quality =
                result.usageQuality() == EmbeddingUsageQuality.ACTUAL
                        ? ExecutionUsageQuality.ACTUAL
                        : ExecutionUsageQuality.ESTIMATED;
        coordinator.settle(
                scope,
                claim,
                leaseOwner,
                component,
                plan,
                units,
                result.costMicros(),
                quality);
        BuildRow progressed = recordProgress(scope, claim, leaseOwner, plan);
        recordBatch(
                plan,
                units,
                quality == ExecutionUsageQuality.ACTUAL
                        ? QualityTag.ACTUAL
                        : QualityTag.ESTIMATED,
                OutcomeTag.SUCCESS);
        return new KnowledgeEmbeddingClaimOutcome(progressed, action, true);
    }

    private KnowledgeEmbeddingClaimOutcome settleOnly(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            KnowledgeEmbeddingBatchPlan plan,
            AdmittedComponent component,
            RecoveryAction action) {
        coordinator.settle(
                scope,
                claim,
                leaseOwner,
                component,
                plan,
                plan.estimatedInputUnits(),
                plan.quote().estimatedCostMicros(),
                ExecutionUsageQuality.ESTIMATED);
        BuildRow progressed = recordProgress(scope, claim, leaseOwner, plan);
        recordBatch(
                plan,
                plan.estimatedInputUnits(),
                QualityTag.ESTIMATED,
                OutcomeTag.REPLAYED);
        return new KnowledgeEmbeddingClaimOutcome(progressed, action, false);
    }

    private KnowledgeEmbeddingClaimOutcome complete(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            KnowledgeEmbeddingBatchPlan plan,
            RecoveryAction action) {
        BuildRow progressed = recordProgress(scope, claim, leaseOwner, plan);
        recordBatch(
                plan,
                plan.estimatedInputUnits(),
                QualityTag.ESTIMATED,
                OutcomeTag.REPLAYED);
        return new KnowledgeEmbeddingClaimOutcome(progressed, action, false);
    }

    private BuildRow recordProgress(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            KnowledgeEmbeddingBatchPlan plan) {
        int count = plan.orderedChunks().getLast().entryOrdinal() + 1;
        return kernel.recordEmbeddingProgressAndRelease(
                scope, claim, leaseOwner, count, count - 1);
    }

    private void recordBatch(
            KnowledgeEmbeddingBatchPlan plan,
            long units,
            QualityTag quality,
            OutcomeTag outcome) {
        int items = plan.orderedChunks().size();
        telemetry.batchItems(outcome, items);
        telemetry.batchUnits(quality, outcome, units);
        telemetry.entries(EntryKindTag.EMBEDDED, outcome, items);
    }

    private KnowledgeEmbeddingClaimOutcome fail(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            RecoveryAction action,
            String code) {
        return fail(scope, claim, leaseOwner, action, code, false);
    }

    private KnowledgeEmbeddingClaimOutcome fail(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            RecoveryAction action,
            String code,
            boolean retryable) {
        return fail(
                scope,
                claim,
                leaseOwner,
                action,
                new KnowledgeIndexBuildFailure(
                        code,
                        retryable
                                ? KnowledgeIndexBuildFailure.Category.TRANSIENT
                                : KnowledgeIndexBuildFailure.Category.VALIDATION,
                        retryable,
                        false),
                action == RecoveryAction.REPLAY || action == RecoveryAction.DISPATCH);
    }

    private KnowledgeEmbeddingClaimOutcome fail(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            RecoveryAction action,
            KnowledgeIndexBuildFailure failure,
            boolean providerInvoked) {
        BuildRow failed =
                kernel.recordFailure(scope, claim, leaseOwner, failure);
        return new KnowledgeEmbeddingClaimOutcome(failed, action, action == RecoveryAction.REPLAY
                || action == RecoveryAction.DISPATCH || providerInvoked);
    }

    private static ComponentState componentState(ExecutionComponentState state) {
        return ComponentState.valueOf(state.name());
    }
}
