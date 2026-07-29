package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.ErrorCategoryTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.OutcomeTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.StaleOperationTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeIndexBuildFailureHandlerTest {
    private final KnowledgeIndexBuildTransitionKernel kernel =
            mock(KnowledgeIndexBuildTransitionKernel.class);
    private final KnowledgeIndexBuildTelemetry telemetry =
            mock(KnowledgeIndexBuildTelemetry.class);
    private final KnowledgeIndexBuildFailureHandler handler =
            new KnowledgeIndexBuildFailureHandler(kernel, telemetry);

    @Test
    void staleLeaseNeverOverwritesTheNewOwner() {
        WorkspaceScope scope = scope();
        BuildRow claim = build(BuildStatus.INDEXING, BuildStep.INDEXING);

        var result = handler.handle(
                scope,
                claim,
                "old-owner",
                false,
                new KnowledgeException(
                        "APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT",
                        KnowledgeException.Category.CONFLICT),
                StaleOperationTag.TRANSITION);

        assertThat(result.outcome()).isEqualTo(OutcomeTag.STALE);
        assertThat(result.errorCategory()).isEqualTo(ErrorCategoryTag.CONFLICT);
        verify(kernel, never()).recordFailure(any(), any(), any(), any());
        verify(telemetry).staleLease(BuildStep.INDEXING, StaleOperationTag.TRANSITION);
    }

    @Test
    void ambiguousProviderOutcomeRequiresReconciliationAndNeverRetries() {
        WorkspaceScope scope = scope();
        BuildRow claim = build(BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        BuildRow failed = build(BuildStatus.FAILED, BuildStep.EMBEDDING);
        when(failed.reconciliationRequired()).thenReturn(true);
        when(kernel.recordFailure(any(), any(), any(), any())).thenReturn(failed);

        var result = handler.handle(
                scope,
                claim,
                "owner",
                true,
                new IllegalStateException("raw provider body and credential"),
                StaleOperationTag.TRANSITION);

        ArgumentCaptor<KnowledgeIndexBuildFailure> normalized =
                ArgumentCaptor.forClass(KnowledgeIndexBuildFailure.class);
        verify(kernel).recordFailure(
                org.mockito.ArgumentMatchers.eq(scope),
                org.mockito.ArgumentMatchers.eq(claim),
                org.mockito.ArgumentMatchers.eq("owner"),
                normalized.capture());
        assertThat(normalized.getValue().code())
                .isEqualTo("APVERO_KNOWLEDGE_INDEX_BUILD_OUTCOME_AMBIGUOUS");
        assertThat(normalized.getValue().category())
                .isEqualTo(KnowledgeIndexBuildFailure.Category.AMBIGUOUS);
        assertThat(normalized.getValue().retryable()).isFalse();
        assertThat(normalized.getValue().reconciliationRequired()).isTrue();
        assertThat(result.outcome()).isEqualTo(OutcomeTag.RECONCILIATION);
    }

    @Test
    void unknownLocalFailureUsesOneStableInternalCode() {
        WorkspaceScope scope = scope();
        BuildRow claim = build(BuildStatus.INDEXING, BuildStep.INDEXING);
        BuildRow failed = build(BuildStatus.FAILED, BuildStep.INDEXING);
        when(kernel.recordFailure(any(), any(), any(), any())).thenReturn(failed);

        handler.handle(
                scope,
                claim,
                "owner",
                false,
                new IllegalStateException("jdbc:postgresql://user:password@host/raw"),
                StaleOperationTag.TRANSITION);

        ArgumentCaptor<KnowledgeIndexBuildFailure> normalized =
                ArgumentCaptor.forClass(KnowledgeIndexBuildFailure.class);
        verify(kernel).recordFailure(
                any(), any(), any(), normalized.capture());
        assertThat(normalized.getValue())
                .isEqualTo(new KnowledgeIndexBuildFailure(
                        "APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_INTERNAL",
                        KnowledgeIndexBuildFailure.Category.INTERNAL,
                        false,
                        false));
    }

    private static BuildRow build(BuildStatus status, BuildStep step) {
        BuildRow build = mock(BuildRow.class);
        when(build.status()).thenReturn(status);
        when(build.currentStep()).thenReturn(step);
        return build;
    }

    private static WorkspaceScope scope() {
        return new WorkspaceScope(UUID.randomUUID(), UUID.randomUUID());
    }
}
