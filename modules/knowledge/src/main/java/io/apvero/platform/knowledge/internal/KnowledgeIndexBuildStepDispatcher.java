package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import java.time.Duration;

final class KnowledgeIndexBuildStepDispatcher {
    private final KnowledgeIndexBuildTransitionKernel kernel;
    private final KnowledgeIndexBuildEmbeddingOrchestrator embedding;
    private final KnowledgeIndexBuildValidationOrchestrator validation;
    private final KnowledgeIndexPublicationCoordinator publication;
    private final EmbeddingCapability embeddings;
    private final KnowledgeIndexBuildRunnerProperties properties;

    KnowledgeIndexBuildStepDispatcher(
            KnowledgeIndexBuildTransitionKernel kernel,
            KnowledgeIndexBuildEmbeddingOrchestrator embedding,
            KnowledgeIndexBuildValidationOrchestrator validation,
            KnowledgeIndexPublicationCoordinator publication,
            EmbeddingCapability embeddings,
            KnowledgeIndexBuildRunnerProperties properties) {
        this.kernel = kernel;
        this.embedding = embedding;
        this.validation = validation;
        this.publication = publication;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    void execute(WorkspaceScope scope, BuildRow claim, String leaseOwner) {
        requireMatchingStep(claim);
        if (claim.status() == BuildStatus.EMBEDDING) {
            requireSafeEmbeddingTimeout(scope, claim);
        }
        BuildRow renewed = kernel.renew(scope, claim, leaseOwner);
        requireMatchingStep(renewed);
        switch (renewed.status()) {
            case EMBEDDING -> embedding.executeClaim(scope, renewed, leaseOwner);
            case INDEXING -> validation.executeClaim(scope, renewed, leaseOwner);
            case VALIDATING -> publication.publish(scope, renewed, leaseOwner);
            default -> throw stateConflict();
        }
    }

    private void requireSafeEmbeddingTimeout(WorkspaceScope scope, BuildRow claim) {
        EmbeddingRouteSnapshot route =
                embeddings.resolveEmbeddingRoute(scope.workspaceId(), claim.embeddingRouteId());
        if (!route.tenantId().equals(scope.tenantId())
                || !route.workspaceId().equals(scope.workspaceId())
                || !route.id().equals(claim.embeddingRouteId())
                || !route.reference().equals(claim.embeddingRouteReference())
                || Duration.ofMillis(route.timeoutMs())
                                .compareTo(properties.externalCallTimeout())
                        > 0) {
            throw new KnowledgeException(
                    "APVERO_KNOWLEDGE_INDEX_BUILD_ROUTE_TIMEOUT_UNSAFE",
                    KnowledgeException.Category.CONFLICT);
        }
    }

    private static void requireMatchingStep(BuildRow claim) {
        if (claim == null
                || (claim.status() == BuildStatus.EMBEDDING
                        && claim.currentStep() != BuildStep.EMBEDDING)
                || (claim.status() == BuildStatus.INDEXING
                        && claim.currentStep() != BuildStep.INDEXING)
                || (claim.status() == BuildStatus.VALIDATING
                        && claim.currentStep() != BuildStep.VALIDATING)
                || (claim.status() != BuildStatus.EMBEDDING
                        && claim.status() != BuildStatus.INDEXING
                        && claim.status() != BuildStatus.VALIDATING)) {
            throw stateConflict();
        }
    }

    private static KnowledgeException stateConflict() {
        return new KnowledgeException(
                "APVERO_KNOWLEDGE_INDEX_BUILD_STATE_CONFLICT",
                KnowledgeException.Category.CONFLICT);
    }
}
