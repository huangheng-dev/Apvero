package io.apvero.platform.knowledge;

import java.util.List;
import java.util.UUID;

public interface KnowledgeBaseCatalog {
    List<KnowledgeBase> list(UUID workspaceId);

    KnowledgeBase create(
            UUID workspaceId,
            CreateKnowledgeBaseCommand command,
            KnowledgeCommandContext context);
}
