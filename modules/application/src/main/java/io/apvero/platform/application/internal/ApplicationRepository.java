package io.apvero.platform.application.internal;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.ApplicationKnowledgeBindingSet;
import io.apvero.platform.application.CreateApplicationCommand;
import io.apvero.platform.application.ReplaceApplicationKnowledgeBindingsCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ApplicationRepository {
    List<AiApplication> findAll(UUID workspaceId);

    Optional<AiApplication> findById(UUID workspaceId, UUID applicationId);

    AiApplication insert(UUID workspaceId, CreateApplicationCommand command);

    AiApplication bindDraft(UUID workspaceId, UUID applicationId, UUID modelRouteId, UUID promptVersionId);

    Optional<ApplicationKnowledgeBindingSet> findDraftKnowledgeBindings(
            UUID workspaceId, UUID applicationId);

    Optional<ApplicationKnowledgeBindingSet> replaceDraftKnowledgeBindings(
            UUID workspaceId,
            UUID applicationId,
            UUID tenantId,
            long expectedApplicationVersion,
            List<ReplaceApplicationKnowledgeBindingsCommand.BindingSelection> bindings);
}
