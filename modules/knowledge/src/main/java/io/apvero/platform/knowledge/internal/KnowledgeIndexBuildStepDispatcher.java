package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildFailureHandler.HandlingResult;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.EntryKindTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.ErrorCategoryTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.OutcomeTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.PublicationTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.PublicationValidationTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.StaleOperationTag;
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
    private final KnowledgeIndexBuildTelemetry telemetry;
    private final KnowledgeIndexBuildFailureHandler failures;

    KnowledgeIndexBuildStepDispatcher(
            KnowledgeIndexBuildTransitionKernel kernel,
            KnowledgeIndexBuildEmbeddingOrchestrator embedding,
            KnowledgeIndexBuildValidationOrchestrator validation,
            KnowledgeIndexPublicationCoordinator publication,
            EmbeddingCapability embeddings,
            KnowledgeIndexBuildRunnerProperties properties,
            KnowledgeIndexBuildTelemetry telemetry,
            KnowledgeIndexBuildFailureHandler failures) {
        this.kernel = kernel;
        this.embedding = embedding;
        this.validation = validation;
        this.publication = publication;
        this.embeddings = embeddings;
        this.properties = properties;
        this.telemetry = telemetry;
        this.failures = failures;
    }

    void execute(WorkspaceScope scope, BuildRow claim, String leaseOwner) {
        BuildRow active = claim;
        BuildStep step = claim.currentStep();
        long startedAt = System.nanoTime();
        try {
            requireMatchingStep(claim);
            if (claim.status() == BuildStatus.EMBEDDING) {
                requireSafeEmbeddingTimeout(scope, claim);
            }
            active = kernel.renew(scope, claim, leaseOwner);
            requireMatchingStep(active);
            Observation observation = dispatch(scope, active, leaseOwner);
            telemetry.stepDuration(
                    step,
                    observation.outcome(),
                    observation.errorCategory(),
                    System.nanoTime() - startedAt);
        } catch (RuntimeException failure) {
            HandlingResult handled = failures.handle(
                    scope,
                    active,
                    leaseOwner,
                    false,
                    failure,
                    staleOperation(step, active == claim));
            if (step == BuildStep.VALIDATING
                    && handled.outcome() != OutcomeTag.STALE) {
                telemetry.publication(PublicationTag.FAILED);
            }
            telemetry.stepDuration(
                    step,
                    handled.outcome(),
                    handled.errorCategory(),
                    System.nanoTime() - startedAt);
        }
    }

    private Observation dispatch(
            WorkspaceScope scope,
            BuildRow renewed,
            String leaseOwner) {
        return switch (renewed.status()) {
            case EMBEDDING -> observeEmbedding(
                    embedding.executeClaim(scope, renewed, leaseOwner));
            case INDEXING -> observeValidation(
                    validation.executeClaim(scope, renewed, leaseOwner));
            case VALIDATING -> observePublication(
                    publication.publish(scope, renewed, leaseOwner));
            default -> throw stateConflict();
        };
    }

    private Observation observeEmbedding(KnowledgeEmbeddingClaimOutcome result) {
        Observation observation = observation(result.build());
        telemetry.recovery(result.action(), observation.outcome());
        if (observation.outcome() == OutcomeTag.RETRY) {
            telemetry.retry(
                    BuildStep.EMBEDDING, observation.errorCategory());
        }
        return observation;
    }

    private Observation observeValidation(KnowledgeIndexValidationClaimOutcome result) {
        Observation observation = observation(result.build());
        PublicationValidationTag validationOutcome =
                result.status() == KnowledgeIndexValidationClaimOutcome.Status.ADVANCED_TO_VALIDATING
                        ? PublicationValidationTag.ADVANCED
                        : PublicationValidationTag.FAILED;
        telemetry.publicationValidation(
                validationOutcome, observation.errorCategory());
        telemetry.entries(
                EntryKindTag.VALIDATED,
                observation.outcome(),
                result.build().validatedEntryCount());
        return observation;
    }

    private Observation observePublication(KnowledgeIndexPublicationOutcome result) {
        PublicationTag publicationOutcome =
                result.status() == KnowledgeIndexPublicationOutcome.Status.PUBLISHED
                        ? PublicationTag.PUBLISHED
                        : PublicationTag.REPLAYED;
        telemetry.publication(publicationOutcome);
        OutcomeTag outcome = publicationOutcome == PublicationTag.PUBLISHED
                ? OutcomeTag.SUCCESS
                : OutcomeTag.REPLAYED;
        telemetry.entries(
                EntryKindTag.REQUESTED,
                outcome,
                result.build().requestedChunkCount());
        return new Observation(outcome, ErrorCategoryTag.NONE);
    }

    private static Observation observation(BuildRow build) {
        ErrorCategoryTag category =
                ErrorCategoryTag.fromStored(build.errorCategory());
        if (build.reconciliationRequired()) {
            return new Observation(OutcomeTag.RECONCILIATION, category);
        }
        return switch (build.status()) {
            case RETRY_WAIT -> new Observation(OutcomeTag.RETRY, category);
            case FAILED, CANCELLED -> new Observation(OutcomeTag.FAILED, category);
            default -> new Observation(OutcomeTag.SUCCESS, ErrorCategoryTag.NONE);
        };
    }

    private static StaleOperationTag staleOperation(
            BuildStep step,
            boolean beforeRenewal) {
        if (beforeRenewal) {
            return StaleOperationTag.RENEW;
        }
        return step == BuildStep.VALIDATING
                ? StaleOperationTag.PUBLICATION
                : StaleOperationTag.TRANSITION;
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

    private record Observation(
            OutcomeTag outcome,
            ErrorCategoryTag errorCategory) {}
}
