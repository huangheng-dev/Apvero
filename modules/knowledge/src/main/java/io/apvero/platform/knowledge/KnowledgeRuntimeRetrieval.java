package io.apvero.platform.knowledge;

import java.util.UUID;

public interface KnowledgeRuntimeRetrieval {
    KnowledgeRuntimeRetrievalResult retrieveForRun(
            UUID workspaceId,
            KnowledgeCommandContext context,
            UUID indexVersionId,
            UUID retrievalPolicyVersionId,
            String query);
}
