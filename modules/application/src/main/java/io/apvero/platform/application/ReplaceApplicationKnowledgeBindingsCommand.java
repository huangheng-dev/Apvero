package io.apvero.platform.application;

import java.util.List;
import java.util.UUID;

public record ReplaceApplicationKnowledgeBindingsCommand(
        long expectedApplicationVersion,
        List<BindingSelection> bindings) {

    public record BindingSelection(
            UUID indexVersionId,
            UUID retrievalPolicyVersionId) {}
}
