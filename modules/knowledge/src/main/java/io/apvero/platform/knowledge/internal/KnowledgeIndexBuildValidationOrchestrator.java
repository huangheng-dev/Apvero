package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexValidationClaimOutcome.Status;

final class KnowledgeIndexBuildValidationOrchestrator {
    private static final String ARTIFACT_ERROR_PREFIX = "APVERO_KNOWLEDGE_ARTIFACT_";
    private static final int MAXIMUM_ERROR_CODE_LENGTH = 120;

    private final KnowledgeIndexArtifactAssembler artifacts;
    private final KnowledgeIndexBuildTransitionKernel kernel;

    KnowledgeIndexBuildValidationOrchestrator(
            KnowledgeIndexArtifactAssembler artifacts,
            KnowledgeIndexBuildTransitionKernel kernel) {
        this.artifacts = artifacts;
        this.kernel = kernel;
    }

    KnowledgeIndexValidationClaimOutcome executeClaim(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner) {
        KnowledgeIndexArtifactManifest artifact;
        try {
            artifact = artifacts.reconstruct(scope, claim);
        } catch (IllegalStateException exception) {
            String code = boundedArtifactCode(exception);
            if (code == null) {
                throw exception;
            }
            BuildRow failed = kernel.recordFailure(
                    scope,
                    claim,
                    leaseOwner,
                    new KnowledgeIndexBuildFailure(
                            code,
                            KnowledgeIndexBuildFailure.Category.VALIDATION,
                            false,
                            false));
            return new KnowledgeIndexValidationClaimOutcome(
                    failed, Status.FAILED_VALIDATION);
        }

        BuildRow validating = kernel.advanceToValidatingAndRelease(
                scope,
                claim,
                leaseOwner,
                artifact.entryCount(),
                artifact.validationDigest());
        return new KnowledgeIndexValidationClaimOutcome(
                validating, Status.ADVANCED_TO_VALIDATING);
    }

    private static String boundedArtifactCode(IllegalStateException exception) {
        String code = exception.getMessage();
        return code != null
                        && code.startsWith(ARTIFACT_ERROR_PREFIX)
                        && code.length() <= MAXIMUM_ERROR_CODE_LENGTH
                ? code
                : null;
    }
}
