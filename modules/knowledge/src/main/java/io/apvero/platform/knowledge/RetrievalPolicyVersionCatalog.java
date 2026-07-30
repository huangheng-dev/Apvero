package io.apvero.platform.knowledge;

import java.util.List;
import java.util.UUID;

public interface RetrievalPolicyVersionCatalog {
    List<RetrievalPolicyVersion> list(UUID workspaceId);

    RetrievalPolicyVersion publish(
            UUID workspaceId,
            CreateRetrievalPolicyVersionCommand command,
            KnowledgeCommandContext context);
}
