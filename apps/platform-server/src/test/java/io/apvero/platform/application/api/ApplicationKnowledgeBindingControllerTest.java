package io.apvero.platform.application.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.application.ApplicationKnowledgeBindingException;
import io.apvero.platform.application.ApplicationKnowledgeBindingSet;
import io.apvero.platform.application.ReplaceApplicationKnowledgeBindingsCommand;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApplicationKnowledgeBindingControllerTest {
    @Test
    void nullJsonRequestReachesStableApplicationValidation() {
        ApplicationCatalog applications = mock(ApplicationCatalog.class);
        ApplicationKnowledgeBindingController controller =
                new ApplicationKnowledgeBindingController(applications);
        UUID workspaceId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        ApplicationKnowledgeBindingException expected =
                new ApplicationKnowledgeBindingException(
                        "APVERO_APPLICATION_KNOWLEDGE_BINDING_INVALID",
                        ApplicationKnowledgeBindingException.Category.BAD_REQUEST);
        when(applications.replaceDraftKnowledgeBindings(
                        eq(workspaceId), eq(applicationId), isNull()))
                .thenThrow(expected);

        assertThatThrownBy(() -> controller.replace(workspaceId, applicationId, null))
                .isSameAs(expected);
        verify(applications).replaceDraftKnowledgeBindings(
                eq(workspaceId), eq(applicationId), isNull());
    }

    @Test
    void preservesExpectedVersionAndClientBindingOrder() {
        ApplicationCatalog applications = mock(ApplicationCatalog.class);
        ApplicationKnowledgeBindingController controller =
                new ApplicationKnowledgeBindingController(applications);
        UUID workspaceId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        var first = request();
        var second = request();
        var expected = new ApplicationKnowledgeBindingSet(applicationId, 8, List.of());
        ArgumentCaptor<ReplaceApplicationKnowledgeBindingsCommand> command =
                ArgumentCaptor.forClass(ReplaceApplicationKnowledgeBindingsCommand.class);
        when(applications.replaceDraftKnowledgeBindings(
                        eq(workspaceId), eq(applicationId), command.capture()))
                .thenReturn(expected);

        assertThat(controller.replace(
                        workspaceId,
                        applicationId,
                        new ApplicationKnowledgeBindingController.ReplaceRequest(
                                7, List.of(first, second))))
                .isEqualTo(expected);
        assertThat(command.getValue().expectedApplicationVersion()).isEqualTo(7);
        assertThat(command.getValue().bindings())
                .extracting(
                        ReplaceApplicationKnowledgeBindingsCommand.BindingSelection::indexVersionId)
                .containsExactly(first.indexVersionId(), second.indexVersionId());
    }

    private static ApplicationKnowledgeBindingController.BindingRequest request() {
        return new ApplicationKnowledgeBindingController.BindingRequest(
                UUID.randomUUID(), UUID.randomUUID());
    }
}
