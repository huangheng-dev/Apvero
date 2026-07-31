package io.apvero.platform.application.api;

import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.application.ApplicationKnowledgeBindingSet;
import io.apvero.platform.application.ReplaceApplicationKnowledgeBindingsCommand;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/applications/{applicationId}/draft/knowledge-bindings")
@ConditionalOnProperty(name = "apvero.knowledge.enabled", havingValue = "true")
final class ApplicationKnowledgeBindingController {
    private final ApplicationCatalog applications;

    ApplicationKnowledgeBindingController(ApplicationCatalog applications) {
        this.applications = applications;
    }

    @GetMapping
    ApplicationKnowledgeBindingSet get(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID applicationId) {
        return applications.getDraftKnowledgeBindings(workspaceId, applicationId);
    }

    @PutMapping
    ApplicationKnowledgeBindingSet replace(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID applicationId,
            @RequestBody(required = false) ReplaceRequest request) {
        ReplaceApplicationKnowledgeBindingsCommand command = request == null
                ? null
                : new ReplaceApplicationKnowledgeBindingsCommand(
                        request.expectedApplicationVersion(),
                        request.bindings() == null
                                ? null
                                : request.bindings().stream()
                                        .map(binding -> new ReplaceApplicationKnowledgeBindingsCommand.BindingSelection(
                                                binding.indexVersionId(),
                                                binding.retrievalPolicyVersionId()))
                                        .toList());
        return applications.replaceDraftKnowledgeBindings(workspaceId, applicationId, command);
    }

    record ReplaceRequest(
            long expectedApplicationVersion,
            List<BindingRequest> bindings) {}

    record BindingRequest(
            UUID indexVersionId,
            UUID retrievalPolicyVersionId) {}
}
