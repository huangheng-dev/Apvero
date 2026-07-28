package io.apvero.platform.knowledge.internal;

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
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

class KnowledgeEmbeddingLeaseCoordinator {
    private static final String ACTOR = "apvero-index-build-runner";

    private final KnowledgeIndexBuildTransitionKernel kernel;
    private final KnowledgeEmbeddingBatchExecutor batches;
    private final ExecutionGovernance governance;

    KnowledgeEmbeddingLeaseCoordinator(
            KnowledgeIndexBuildTransitionKernel kernel,
            KnowledgeEmbeddingBatchExecutor batches,
            ExecutionGovernance governance) {
        this.kernel = kernel;
        this.batches = batches;
        this.governance = governance;
    }

    @Transactional
    AdmittedComponent admitAndInspect(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            KnowledgeEmbeddingBatchPlan plan) {
        kernel.requireActiveLease(scope, claim, leaseOwner);
        ExecutionAdmission admission = governance.admit(new ExecutionReservationRequest(
                scope.workspaceId(),
                ExecutionSubject.knowledgeIngestion(claim.id()),
                ACTOR,
                traceIdentity(claim, plan),
                List.of(new ExecutionComponentRequest(
                        ExecutionComponentType.EMBEDDING_INDEX,
                        claim.embeddingRouteId(),
                        claim.embeddingRouteReference(),
                        plan.idempotencyIdentity(),
                        plan.estimatedInputUnits(),
                        plan.quote().estimatedCostMicros(),
                        plan.quote().currency()))));
        ExecutionComponentSnapshot snapshot = governance.findComponent(
                        scope.workspaceId(),
                        admission.reservationId(),
                        plan.idempotencyIdentity())
                .orElseThrow(() -> new IllegalStateException(
                        "APVERO_EXECUTION_COMPONENT_NOT_FOUND"));
        requireEqual(plan, snapshot);
        return new AdmittedComponent(admission.reservationId(), snapshot);
    }

    @Transactional
    void markDispatched(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            AdmittedComponent component,
            KnowledgeEmbeddingBatchPlan plan) {
        kernel.requireActiveLease(scope, claim, leaseOwner);
        governance.markDispatched(new ExecutionComponentDispatch(
                component.reservationId(), plan.idempotencyIdentity(), null));
    }

    @Transactional
    void enrichProviderIdentity(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            AdmittedComponent component,
            KnowledgeEmbeddingBatchPlan plan,
            String providerRequestIdentity) {
        if (providerRequestIdentity == null) {
            return;
        }
        kernel.requireActiveLease(scope, claim, leaseOwner);
        governance.markDispatched(new ExecutionComponentDispatch(
                component.reservationId(),
                plan.idempotencyIdentity(),
                providerRequestIdentity));
    }

    @Transactional
    void persist(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            KnowledgeEmbeddingBatchPlan plan,
            io.apvero.platform.capability.EmbeddingExecutionResult result) {
        kernel.requireActiveLease(scope, claim, leaseOwner);
        batches.persistUnderLease(plan, result, claim, leaseOwner);
    }

    @Transactional
    void settle(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            AdmittedComponent component,
            KnowledgeEmbeddingBatchPlan plan,
            long actualUnits,
            long actualCostMicros,
            ExecutionUsageQuality usageQuality) {
        kernel.requireActiveLease(scope, claim, leaseOwner);
        if (component.snapshot().state() == ExecutionComponentState.RESERVED) {
            governance.markDispatched(new ExecutionComponentDispatch(
                    component.reservationId(), plan.idempotencyIdentity(), null));
        }
        governance.settle(new ExecutionComponentSettlement(
                component.reservationId(),
                plan.idempotencyIdentity(),
                actualUnits,
                actualCostMicros,
                plan.quote().currency(),
                usageQuality,
                true,
                null));
    }

    @Transactional
    BuildRow requireReconciliationAndFail(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            AdmittedComponent component,
            KnowledgeEmbeddingBatchPlan plan) {
        kernel.requireActiveLease(scope, claim, leaseOwner);
        governance.requireReconciliation(new ExecutionComponentReconciliation(
                component.reservationId(),
                plan.idempotencyIdentity(),
                "APVERO_EMBEDDING_OUTCOME_AMBIGUOUS"));
        return kernel.recordFailure(
                scope,
                claim,
                leaseOwner,
                new KnowledgeIndexBuildFailure(
                        "APVERO_EMBEDDING_OUTCOME_AMBIGUOUS",
                        KnowledgeIndexBuildFailure.Category.AMBIGUOUS,
                        false,
                        true));
    }

    private static void requireEqual(
            KnowledgeEmbeddingBatchPlan plan,
            ExecutionComponentSnapshot snapshot) {
        if (snapshot.type() != ExecutionComponentType.EMBEDDING_INDEX
                || !snapshot.modelRouteId().equals(plan.build().embeddingRouteId())
                || !snapshot.modelRouteReference().equals(
                        plan.build().embeddingRouteReference())
                || !snapshot.idempotencyIdentity().equals(plan.idempotencyIdentity())
                || snapshot.estimatedUnits() != plan.estimatedInputUnits()
                || snapshot.estimatedCostMicros() != plan.quote().estimatedCostMicros()
                || !snapshot.currency().equals(plan.quote().currency())) {
            throw new IllegalStateException(
                    "APVERO_EXECUTION_RESERVATION_IDEMPOTENCY_CONFLICT");
        }
    }

    private static String traceIdentity(
            BuildRow claim,
            KnowledgeEmbeddingBatchPlan plan) {
        return "knowledge-embedding:" + claim.id() + ':' + plan.batchOrdinal();
    }

    record AdmittedComponent(
            java.util.UUID reservationId,
            ExecutionComponentSnapshot snapshot) {}
}
