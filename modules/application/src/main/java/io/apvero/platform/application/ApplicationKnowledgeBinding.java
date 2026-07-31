package io.apvero.platform.application;

import java.util.UUID;

public record ApplicationKnowledgeBinding(
        UUID indexVersionId,
        UUID retrievalPolicyVersionId,
        int bindingOrder) {}
