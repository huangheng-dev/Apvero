package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeIndexBuildStepDispatcherTest {
    private final KnowledgeIndexBuildTransitionKernel kernel =
            mock(KnowledgeIndexBuildTransitionKernel.class);
    private final KnowledgeIndexBuildEmbeddingOrchestrator embedding =
            mock(KnowledgeIndexBuildEmbeddingOrchestrator.class);
    private final KnowledgeIndexBuildValidationOrchestrator validation =
            mock(KnowledgeIndexBuildValidationOrchestrator.class);
    private final KnowledgeIndexPublicationCoordinator publication =
            mock(KnowledgeIndexPublicationCoordinator.class);
    private final EmbeddingCapability embeddings = mock(EmbeddingCapability.class);

    @Test
    void renewsAndDispatchesEachActiveStepExactly() {
        WorkspaceScope scope = scope();
        String owner = "runner";
        KnowledgeIndexBuildStepDispatcher dispatcher = dispatcher(Duration.ofSeconds(30));

        BuildRow embeddingClaim = build(scope, BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        BuildRow renewedEmbedding = build(scope, BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        safeRoute(scope, embeddingClaim, 30_000);
        when(kernel.renew(scope, embeddingClaim, owner)).thenReturn(renewedEmbedding);
        dispatcher.execute(scope, embeddingClaim, owner);
        verify(embedding).executeClaim(scope, renewedEmbedding, owner);

        BuildRow indexingClaim = build(scope, BuildStatus.INDEXING, BuildStep.INDEXING);
        BuildRow renewedIndexing = build(scope, BuildStatus.INDEXING, BuildStep.INDEXING);
        when(kernel.renew(scope, indexingClaim, owner)).thenReturn(renewedIndexing);
        dispatcher.execute(scope, indexingClaim, owner);
        verify(validation).executeClaim(scope, renewedIndexing, owner);

        BuildRow validatingClaim = build(scope, BuildStatus.VALIDATING, BuildStep.VALIDATING);
        BuildRow renewedValidating = build(scope, BuildStatus.VALIDATING, BuildStep.VALIDATING);
        when(kernel.renew(scope, validatingClaim, owner)).thenReturn(renewedValidating);
        dispatcher.execute(scope, validatingClaim, owner);
        verify(publication).publish(scope, renewedValidating, owner);
    }

    @Test
    void rejectsUnsafeRouteTimeoutBeforeRenewalOrProviderDispatch() {
        WorkspaceScope scope = scope();
        BuildRow claim = build(scope, BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        safeRoute(scope, claim, 30_001);
        KnowledgeIndexBuildStepDispatcher dispatcher = dispatcher(Duration.ofSeconds(30));

        assertThatThrownBy(() -> dispatcher.execute(scope, claim, "runner"))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_ROUTE_TIMEOUT_UNSAFE");

        verifyNoInteractions(kernel, embedding, validation, publication);
    }

    @Test
    void rejectsMismatchedOrTerminalStateWithoutMutation() {
        WorkspaceScope scope = scope();
        BuildRow mismatch = build(scope, BuildStatus.INDEXING, BuildStep.EMBEDDING);
        KnowledgeIndexBuildStepDispatcher dispatcher = dispatcher(Duration.ofSeconds(30));

        assertThatThrownBy(() -> dispatcher.execute(scope, mismatch, "runner"))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_STATE_CONFLICT");

        verifyNoInteractions(kernel, embedding, validation, publication, embeddings);
    }

    private KnowledgeIndexBuildStepDispatcher dispatcher(Duration externalTimeout) {
        return new KnowledgeIndexBuildStepDispatcher(
                kernel,
                embedding,
                validation,
                publication,
                embeddings,
                properties(true, 4, externalTimeout, Duration.ofSeconds(30)));
    }

    private void safeRoute(WorkspaceScope scope, BuildRow claim, int timeoutMs) {
        EmbeddingRouteSnapshot route = mock(EmbeddingRouteSnapshot.class);
        UUID routeId = claim.embeddingRouteId();
        String routeReference = claim.embeddingRouteReference();
        when(route.tenantId()).thenReturn(scope.tenantId());
        when(route.workspaceId()).thenReturn(scope.workspaceId());
        when(route.id()).thenReturn(routeId);
        when(route.reference()).thenReturn(routeReference);
        when(route.timeoutMs()).thenReturn(timeoutMs);
        when(embeddings.resolveEmbeddingRoute(scope.workspaceId(), routeId))
                .thenReturn(route);
    }

    private static BuildRow build(
            WorkspaceScope scope, BuildStatus status, BuildStep step) {
        BuildRow build = mock(BuildRow.class);
        when(build.tenantId()).thenReturn(scope.tenantId());
        when(build.workspaceId()).thenReturn(scope.workspaceId());
        when(build.embeddingRouteId()).thenReturn(UUID.randomUUID());
        when(build.embeddingRouteReference()).thenReturn("embedding@1");
        when(build.status()).thenReturn(status);
        when(build.currentStep()).thenReturn(step);
        return build;
    }

    private static WorkspaceScope scope() {
        return new WorkspaceScope(UUID.randomUUID(), UUID.randomUUID());
    }

    static KnowledgeIndexBuildRunnerProperties properties(
            boolean enabled,
            int concurrency,
            Duration externalTimeout,
            Duration gracefulDrain) {
        return new KnowledgeIndexBuildRunnerProperties(
                enabled,
                4,
                concurrency,
                Duration.ofSeconds(60),
                externalTimeout,
                Duration.ofSeconds(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofMinutes(5),
                gracefulDrain);
    }
}
