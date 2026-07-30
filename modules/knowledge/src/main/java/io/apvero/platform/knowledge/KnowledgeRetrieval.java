package io.apvero.platform.knowledge;

import java.util.UUID;

public interface KnowledgeRetrieval {
    KnowledgeRetrievalResult retrieve(
            UUID workspaceId,
            KnowledgeCommandContext context,
            UUID indexVersionId,
            UUID retrievalPolicyVersionId,
            String query);
}
