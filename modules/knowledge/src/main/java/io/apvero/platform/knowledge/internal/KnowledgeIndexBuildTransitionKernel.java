package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class KnowledgeIndexBuildTransitionKernel {
    private static final Pattern DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");
    private static final int MAXIMUM_OWNER_LENGTH = 200;

    private final KnowledgeIndexPersistenceRepository repository;
    private final KnowledgeIndexBuildRunnerProperties properties;
    private final KnowledgeIndexBuildBackoffPolicy backoff;

    KnowledgeIndexBuildTransitionKernel(
            KnowledgeIndexPersistenceRepository repository,
            KnowledgeIndexBuildRunnerProperties properties,
            KnowledgeIndexBuildBackoffPolicy backoff) {
        this.repository = repository;
        this.properties = properties;
        this.backoff = backoff;
    }

    @Transactional
    List<BuildRow> claim(WorkspaceScope scope, String leaseOwner, int capacity) {
        requireScope(scope);
        String owner = requireOwner(leaseOwner);
        if (capacity < 1) {
            throw invalidInput();
        }
        return repository.claimBuilds(
                scope,
                owner,
                properties.leaseDuration(),
                Math.min(capacity, properties.claimBatch()));
    }

    @Transactional
    BuildRow renew(WorkspaceScope scope, BuildRow claim, String leaseOwner) {
        requireClaim(scope, claim, leaseOwner);
        requireActive(claim);
        return repository.renewBuildLease(
                        scope,
                        claim.id(),
                        claim.lockVersion(),
                        leaseOwner,
                        claim.status(),
                        claim.currentStep(),
                        properties.leaseDuration())
                .orElseThrow(KnowledgeIndexBuildTransitionKernel::leaseConflict);
    }

    @Transactional
    BuildRow requireActiveLease(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner) {
        requireClaim(scope, claim, leaseOwner);
        requireActive(claim);
        return repository.lockActiveBuildLease(
                        scope,
                        claim.id(),
                        claim.lockVersion(),
                        leaseOwner,
                        claim.status(),
                        claim.currentStep())
                .orElseThrow(KnowledgeIndexBuildTransitionKernel::leaseConflict);
    }

    @Transactional
    BuildRow recordEmbeddingProgressAndRelease(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            int embeddedEntryCount,
            int lastDurableChunkOrdinal) {
        requireClaim(scope, claim, leaseOwner);
        requireState(claim, BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        if (embeddedEntryCount < claim.embeddedEntryCount()
                || embeddedEntryCount > claim.requestedChunkCount()
                || lastDurableChunkOrdinal < 0
                || lastDurableChunkOrdinal != embeddedEntryCount - 1
                || (claim.lastDurableChunkOrdinal() != null
                        && lastDurableChunkOrdinal < claim.lastDurableChunkOrdinal())) {
            throw invalidProgress();
        }
        return repository.recordEmbeddingProgressAndRelease(
                        scope,
                        claim.id(),
                        claim.lockVersion(),
                        leaseOwner,
                        embeddedEntryCount,
                        lastDurableChunkOrdinal)
                .orElseThrow(KnowledgeIndexBuildTransitionKernel::leaseConflict);
    }

    @Transactional
    BuildRow advanceToIndexingAndRelease(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner) {
        requireClaim(scope, claim, leaseOwner);
        requireState(claim, BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        if (claim.embeddedEntryCount() != claim.requestedChunkCount()) {
            throw stateConflict();
        }
        return repository.advanceBuildToIndexingAndRelease(
                        scope, claim.id(), claim.lockVersion(), leaseOwner)
                .orElseThrow(KnowledgeIndexBuildTransitionKernel::leaseConflict);
    }

    @Transactional
    BuildRow advanceToValidatingAndRelease(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            int validatedEntryCount,
            String validationDigest) {
        requireClaim(scope, claim, leaseOwner);
        requireState(claim, BuildStatus.INDEXING, BuildStep.INDEXING);
        if (validatedEntryCount != claim.requestedChunkCount()
                || validationDigest == null
                || !DIGEST.matcher(validationDigest).matches()) {
            throw stateConflict();
        }
        return repository.advanceBuildToValidatingAndRelease(
                        scope,
                        claim.id(),
                        claim.lockVersion(),
                        leaseOwner,
                        validatedEntryCount,
                        validationDigest)
                .orElseThrow(KnowledgeIndexBuildTransitionKernel::leaseConflict);
    }

    @Transactional
    BuildRow recordFailure(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            KnowledgeIndexBuildFailure failure) {
        requireClaim(scope, claim, leaseOwner);
        requireActive(claim);
        if (failure == null) {
            throw invalidInput();
        }

        boolean scheduleRetry = failure.retryable()
                && !failure.reconciliationRequired()
                && claim.attemptCount() < claim.maximumAttempts();
        BuildStatus failureStatus = scheduleRetry ? BuildStatus.RETRY_WAIT : BuildStatus.FAILED;
        boolean retryable = failure.retryable() && !failure.reconciliationRequired();
        Duration retryDelay = scheduleRetry ? backoff.delay(claim.attemptCount()) : null;
        return repository.failLeasedBuild(
                        scope,
                        claim.id(),
                        claim.lockVersion(),
                        leaseOwner,
                        claim.status(),
                        claim.currentStep(),
                        failureStatus,
                        retryable,
                        retryDelay,
                        failure.code(),
                        failure.category().name(),
                        failure.reconciliationRequired())
                .orElseThrow(KnowledgeIndexBuildTransitionKernel::leaseConflict);
    }

    private static void requireClaim(WorkspaceScope scope, BuildRow claim, String leaseOwner) {
        requireScope(scope);
        requireOwner(leaseOwner);
        if (claim == null
                || !scope.tenantId().equals(claim.tenantId())
                || !scope.workspaceId().equals(claim.workspaceId())
                || !leaseOwner.equals(claim.leaseOwner())
                || claim.leaseUntil() == null) {
            throw leaseConflict();
        }
    }

    private static void requireActive(BuildRow claim) {
        if (claim.status() != BuildStatus.EMBEDDING
                && claim.status() != BuildStatus.INDEXING
                && claim.status() != BuildStatus.VALIDATING) {
            throw stateConflict();
        }
        if (!claim.status().name().equals(claim.currentStep().name())) {
            throw stateConflict();
        }
    }

    private static void requireState(BuildRow claim, BuildStatus status, BuildStep step) {
        if (claim.status() != status || claim.currentStep() != step) {
            throw stateConflict();
        }
    }

    private static WorkspaceScope requireScope(WorkspaceScope scope) {
        if (scope == null || scope.tenantId() == null || scope.workspaceId() == null) {
            throw invalidInput();
        }
        return scope;
    }

    private static String requireOwner(String owner) {
        if (owner == null
                || owner.isBlank()
                || !owner.equals(owner.trim())
                || owner.codePointCount(0, owner.length()) > MAXIMUM_OWNER_LENGTH) {
            throw invalidInput();
        }
        return owner;
    }

    private static KnowledgeException invalidInput() {
        return new KnowledgeException(
                "APVERO_KNOWLEDGE_INDEX_BUILD_KERNEL_INPUT_INVALID",
                KnowledgeException.Category.BAD_REQUEST);
    }

    private static KnowledgeException invalidProgress() {
        return new KnowledgeException(
                "APVERO_KNOWLEDGE_INDEX_BUILD_PROGRESS_INVALID",
                KnowledgeException.Category.CONFLICT);
    }

    private static KnowledgeException stateConflict() {
        return new KnowledgeException(
                "APVERO_KNOWLEDGE_INDEX_BUILD_STATE_CONFLICT",
                KnowledgeException.Category.CONFLICT);
    }

    private static KnowledgeException leaseConflict() {
        return new KnowledgeException(
                "APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT",
                KnowledgeException.Category.CONFLICT);
    }
}
