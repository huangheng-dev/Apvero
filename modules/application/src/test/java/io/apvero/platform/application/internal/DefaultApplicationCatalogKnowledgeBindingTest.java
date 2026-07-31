package io.apvero.platform.application.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.ApplicationKnowledgeBinding;
import io.apvero.platform.application.ApplicationKnowledgeBindingException;
import io.apvero.platform.application.ApplicationKnowledgeBindingSet;
import io.apvero.platform.application.ApplicationStatus;
import io.apvero.platform.application.ReplaceApplicationKnowledgeBindingsCommand;
import io.apvero.platform.application.RuntimeMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultApplicationCatalogKnowledgeBindingTest {
    private final ApplicationRepository repository = mock(ApplicationRepository.class);
    private final DefaultApplicationCatalog catalog = new DefaultApplicationCatalog(repository);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();

    @Test
    void replacesOpaqueBindingsInClientOrderWithOptimisticVersion() {
        AiApplication application = application(RuntimeMode.RAG, 7);
        var first = selection();
        var second = selection();
        var command = new ReplaceApplicationKnowledgeBindingsCommand(7, List.of(first, second));
        var expected = new ApplicationKnowledgeBindingSet(applicationId, 8, List.of(
                new ApplicationKnowledgeBinding(first.indexVersionId(), first.retrievalPolicyVersionId(), 0),
                new ApplicationKnowledgeBinding(second.indexVersionId(), second.retrievalPolicyVersionId(), 1)));
        when(repository.findById(workspaceId, applicationId)).thenReturn(Optional.of(application));
        when(repository.replaceDraftKnowledgeBindings(
                        workspaceId, applicationId, tenantId, 7, command.bindings()))
                .thenReturn(Optional.of(expected));

        assertThat(catalog.replaceDraftKnowledgeBindings(workspaceId, applicationId, command))
                .isEqualTo(expected);
    }

    @Test
    void permitsAnEmptyRagDraftButRejectsBindingsForOtherRuntimeModes() {
        AiApplication rag = application(RuntimeMode.RAG, 3);
        var empty = new ApplicationKnowledgeBindingSet(applicationId, 4, List.of());
        when(repository.findById(workspaceId, applicationId)).thenReturn(Optional.of(rag));
        when(repository.replaceDraftKnowledgeBindings(
                        workspaceId, applicationId, tenantId, 3, List.of()))
                .thenReturn(Optional.of(empty));
        assertThat(catalog.replaceDraftKnowledgeBindings(
                        workspaceId,
                        applicationId,
                        new ReplaceApplicationKnowledgeBindingsCommand(3, List.of())))
                .isEqualTo(empty);

        when(repository.findById(workspaceId, applicationId))
                .thenReturn(Optional.of(application(RuntimeMode.CHAT, 4)));
        assertCode(
                () -> catalog.replaceDraftKnowledgeBindings(
                        workspaceId,
                        applicationId,
                        new ReplaceApplicationKnowledgeBindingsCommand(4, List.of(selection()))),
                "APVERO_APPLICATION_KNOWLEDGE_BINDING_MODE_INVALID");
    }

    @Test
    void rejectsNullDuplicateAndOversizedSelectionsBeforePersistence() {
        when(repository.findById(workspaceId, applicationId))
                .thenReturn(Optional.of(application(RuntimeMode.RAG, 2)));
        var duplicate = selection();

        assertCode(
                () -> catalog.replaceDraftKnowledgeBindings(workspaceId, applicationId, null),
                "APVERO_APPLICATION_KNOWLEDGE_BINDING_INVALID");
        assertCode(
                () -> catalog.replaceDraftKnowledgeBindings(
                        workspaceId,
                        applicationId,
                        new ReplaceApplicationKnowledgeBindingsCommand(
                                2, List.of(duplicate, duplicate))),
                "APVERO_APPLICATION_KNOWLEDGE_BINDING_INVALID");
        assertCode(
                () -> catalog.replaceDraftKnowledgeBindings(
                        workspaceId,
                        applicationId,
                        new ReplaceApplicationKnowledgeBindingsCommand(
                                2,
                                java.util.stream.IntStream.range(0, 17)
                                        .mapToObj(ignored -> selection())
                                        .toList())),
                "APVERO_APPLICATION_KNOWLEDGE_BINDING_INVALID");
        verify(repository, never()).replaceDraftKnowledgeBindings(
                any(), any(), any(), eq(2L), any());
    }

    @Test
    void reportsAStableConflictWhenTheExpectedVersionLosesTheRace() {
        when(repository.findById(workspaceId, applicationId))
                .thenReturn(Optional.of(application(RuntimeMode.RAG, 9)));
        when(repository.replaceDraftKnowledgeBindings(
                        eq(workspaceId), eq(applicationId), eq(tenantId), eq(9L), any()))
                .thenReturn(Optional.empty());

        assertCode(
                () -> catalog.replaceDraftKnowledgeBindings(
                        workspaceId,
                        applicationId,
                        new ReplaceApplicationKnowledgeBindingsCommand(9, List.of(selection()))),
                "APVERO_APPLICATION_DRAFT_VERSION_CONFLICT");
    }

    @Test
    void readsTheBindingSetWithoutResolvingKnowledge() {
        var expected = new ApplicationKnowledgeBindingSet(applicationId, 5, List.of());
        when(repository.findDraftKnowledgeBindings(workspaceId, applicationId))
                .thenReturn(Optional.of(expected));

        assertThat(catalog.getDraftKnowledgeBindings(workspaceId, applicationId))
                .isEqualTo(expected);
        verify(repository).findDraftKnowledgeBindings(workspaceId, applicationId);
    }

    private AiApplication application(RuntimeMode runtimeMode, long version) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AiApplication(
                applicationId,
                tenantId,
                workspaceId,
                "support",
                "Support",
                "",
                runtimeMode,
                ApplicationStatus.DRAFT,
                null,
                null,
                version,
                now,
                now);
    }

    private static ReplaceApplicationKnowledgeBindingsCommand.BindingSelection selection() {
        return new ReplaceApplicationKnowledgeBindingsCommand.BindingSelection(
                UUID.randomUUID(), UUID.randomUUID());
    }

    private static void assertCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ApplicationKnowledgeBindingException.class)
                .hasMessage(code);
    }
}
