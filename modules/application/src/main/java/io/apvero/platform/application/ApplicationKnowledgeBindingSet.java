package io.apvero.platform.application;

import java.util.List;
import java.util.UUID;

public record ApplicationKnowledgeBindingSet(
        UUID applicationId,
        long applicationVersion,
        List<ApplicationKnowledgeBinding> bindings) {

    public ApplicationKnowledgeBindingSet {
        bindings = List.copyOf(bindings);
    }
}
