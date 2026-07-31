package io.apvero.platform.application.internal;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.application.ApplicationKnowledgeBindingException;
import io.apvero.platform.application.ApplicationKnowledgeBindingSet;
import io.apvero.platform.application.ApplicationNotFoundException;
import io.apvero.platform.application.BindApplicationDraftCommand;
import io.apvero.platform.application.CreateApplicationCommand;
import io.apvero.platform.application.ReplaceApplicationKnowledgeBindingsCommand;
import io.apvero.platform.application.RuntimeMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultApplicationCatalog implements ApplicationCatalog {
    private final ApplicationRepository repository;

    public DefaultApplicationCatalog(ApplicationRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<AiApplication> list(UUID workspaceId) {
        return repository.findAll(workspaceId);
    }

    @Override
    public AiApplication get(UUID workspaceId, UUID applicationId) {
        return repository.findById(workspaceId, applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }

    @Override
    @Transactional
    public AiApplication create(UUID workspaceId, CreateApplicationCommand command) {
        return repository.insert(workspaceId, command);
    }

    @Override
    @Transactional
    public AiApplication bindDraft(UUID workspaceId, UUID applicationId, BindApplicationDraftCommand command) {
        get(workspaceId, applicationId);
        return repository.bindDraft(workspaceId, applicationId, command.modelRouteId(), command.promptVersionId());
    }

    @Override
    public ApplicationKnowledgeBindingSet getDraftKnowledgeBindings(
            UUID workspaceId, UUID applicationId) {
        return repository.findDraftKnowledgeBindings(workspaceId, applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }

    @Override
    @Transactional
    public ApplicationKnowledgeBindingSet replaceDraftKnowledgeBindings(
            UUID workspaceId,
            UUID applicationId,
            ReplaceApplicationKnowledgeBindingsCommand command) {
        AiApplication application = get(workspaceId, applicationId);
        List<ReplaceApplicationKnowledgeBindingsCommand.BindingSelection> bindings =
                validate(application, command);
        return repository.replaceDraftKnowledgeBindings(
                        workspaceId,
                        applicationId,
                        application.tenantId(),
                        command.expectedApplicationVersion(),
                        bindings)
                .orElseThrow(() -> problem(
                        "APVERO_APPLICATION_DRAFT_VERSION_CONFLICT",
                        ApplicationKnowledgeBindingException.Category.CONFLICT));
    }

    private static List<ReplaceApplicationKnowledgeBindingsCommand.BindingSelection> validate(
            AiApplication application,
            ReplaceApplicationKnowledgeBindingsCommand command) {
        if (command == null
                || command.expectedApplicationVersion() < 1
                || command.bindings() == null
                || command.bindings().size() > 16) {
            throw problem(
                    "APVERO_APPLICATION_KNOWLEDGE_BINDING_INVALID",
                    ApplicationKnowledgeBindingException.Category.BAD_REQUEST);
        }
        List<ReplaceApplicationKnowledgeBindingsCommand.BindingSelection> bindings =
                List.copyOf(command.bindings());
        if (application.runtimeMode() != RuntimeMode.RAG && !bindings.isEmpty()) {
            throw problem(
                    "APVERO_APPLICATION_KNOWLEDGE_BINDING_MODE_INVALID",
                    ApplicationKnowledgeBindingException.Category.CONFLICT);
        }
        Set<BindingKey> unique = new HashSet<>();
        for (var binding : bindings) {
            if (binding == null
                    || binding.indexVersionId() == null
                    || binding.retrievalPolicyVersionId() == null
                    || !unique.add(new BindingKey(
                            binding.indexVersionId(), binding.retrievalPolicyVersionId()))) {
                throw problem(
                        "APVERO_APPLICATION_KNOWLEDGE_BINDING_INVALID",
                        ApplicationKnowledgeBindingException.Category.BAD_REQUEST);
            }
        }
        return bindings;
    }

    private static ApplicationKnowledgeBindingException problem(
            String code, ApplicationKnowledgeBindingException.Category category) {
        return new ApplicationKnowledgeBindingException(code, category);
    }

    private record BindingKey(UUID indexVersionId, UUID retrievalPolicyVersionId) {}
}
