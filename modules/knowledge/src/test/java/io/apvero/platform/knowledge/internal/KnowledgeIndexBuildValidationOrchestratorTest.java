package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexValidationClaimOutcome.Status;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeIndexBuildValidationOrchestratorTest {
    private final WorkspaceScope scope =
            new WorkspaceScope(UUID.randomUUID(), UUID.randomUUID());
    private final UUID buildId = UUID.randomUUID();
    private final UUID indexId = UUID.randomUUID();
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private final UUID routeId = UUID.randomUUID();
    private final BuildRow claim = build(BuildStatus.INDEXING, BuildStep.INDEXING);
    private final KnowledgeIndexArtifactAssembler artifacts =
            mock(KnowledgeIndexArtifactAssembler.class);
    private final KnowledgeIndexBuildTransitionKernel kernel =
            mock(KnowledgeIndexBuildTransitionKernel.class);
    private final KnowledgeIndexBuildValidationOrchestrator orchestrator =
            new KnowledgeIndexBuildValidationOrchestrator(artifacts, kernel);

    @Test
    void completeArtifactAdvancesWithCanonicalCountAndDigest() {
        KnowledgeIndexArtifactManifest artifact = manifest();
        BuildRow validating = build(BuildStatus.VALIDATING, BuildStep.VALIDATING);
        when(artifacts.reconstruct(scope, claim)).thenReturn(artifact);
        when(kernel.advanceToValidatingAndRelease(
                        scope, claim, "validation-worker", 1, artifact.validationDigest()))
                .thenReturn(validating);

        KnowledgeIndexValidationClaimOutcome outcome =
                orchestrator.executeClaim(scope, claim, "validation-worker");

        assertThat(outcome.status()).isEqualTo(Status.ADVANCED_TO_VALIDATING);
        assertThat(outcome.build()).isEqualTo(validating);
        verify(kernel, never()).recordFailure(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void boundedArtifactCorruptionFailsTheLeasedBuildWithoutRetry() {
        when(artifacts.reconstruct(scope, claim)).thenThrow(
                new IllegalStateException(
                        "APVERO_KNOWLEDGE_ARTIFACT_VECTOR_DIGEST_MISMATCH"));
        BuildRow failed = build(BuildStatus.FAILED, BuildStep.INDEXING);
        ArgumentCaptor<KnowledgeIndexBuildFailure> failure =
                ArgumentCaptor.forClass(KnowledgeIndexBuildFailure.class);
        when(kernel.recordFailure(
                        org.mockito.ArgumentMatchers.eq(scope),
                        org.mockito.ArgumentMatchers.eq(claim),
                        org.mockito.ArgumentMatchers.eq("validation-worker"),
                        failure.capture()))
                .thenReturn(failed);

        KnowledgeIndexValidationClaimOutcome outcome =
                orchestrator.executeClaim(scope, claim, "validation-worker");

        assertThat(outcome.status()).isEqualTo(Status.FAILED_VALIDATION);
        assertThat(outcome.build()).isEqualTo(failed);
        assertThat(failure.getValue())
                .isEqualTo(new KnowledgeIndexBuildFailure(
                        "APVERO_KNOWLEDGE_ARTIFACT_VECTOR_DIGEST_MISMATCH",
                        KnowledgeIndexBuildFailure.Category.VALIDATION,
                        false,
                        false));
        verify(kernel, never()).advanceToValidatingAndRelease(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unexpectedInternalFailureIsNotMisreportedAsArtifactCorruption() {
        when(artifacts.reconstruct(scope, claim))
                .thenThrow(new IllegalStateException("unexpected"));

        assertThatThrownBy(() ->
                        orchestrator.executeClaim(scope, claim, "validation-worker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unexpected");
        verify(kernel, never()).recordFailure(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private KnowledgeIndexArtifactManifest manifest() {
        return new KnowledgeIndexArtifactManifest(
                scope.tenantId(),
                scope.workspaceId(),
                claim.id(),
                claim.knowledgeIndexId(),
                claim.knowledgeBaseId(),
                claim.requestedVersion(),
                claim.sourceSetDigest(),
                claim.embeddingRouteId(),
                claim.embeddingRouteReference(),
                claim.vectorDimension(),
                claim.maximumInputTokens(),
                claim.maximumBatchSize(),
                claim.normalization(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new KnowledgeIndexArtifactManifest.EntryManifest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        0,
                        0,
                        digest('a'),
                        digest('b'))),
                digest('c'),
                digest('d'));
    }

    private BuildRow build(BuildStatus status, BuildStep step) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new BuildRow(
                buildId,
                scope.tenantId(),
                scope.workspaceId(),
                indexId,
                knowledgeBaseId,
                "1.0.0",
                routeId,
                "embedding-route@1",
                3,
                8_192,
                64,
                "L2",
                digest('1'),
                digest('2'),
                1,
                1,
                status,
                step,
                1,
                3,
                false,
                null,
                status == BuildStatus.INDEXING ? "validation-worker" : null,
                status == BuildStatus.INDEXING ? now.plusMinutes(1) : null,
                status == BuildStatus.INDEXING ? 2 : 3,
                false,
                1,
                status == BuildStatus.VALIDATING ? 1 : 0,
                0,
                status == BuildStatus.VALIDATING ? digest('c') : null,
                null,
                null,
                null,
                null,
                false,
                "{}",
                now,
                status == BuildStatus.FAILED ? now : null,
                now,
                now);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
