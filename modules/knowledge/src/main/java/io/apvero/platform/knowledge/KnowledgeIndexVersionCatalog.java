package io.apvero.platform.knowledge;

import java.util.List;
import java.util.UUID;

public interface KnowledgeIndexVersionCatalog {
    List<KnowledgeIndexVersion> list(UUID workspaceId, UUID indexId);

    KnowledgeIndexVersion get(UUID workspaceId, UUID indexVersionId);

    KnowledgeIndexVersion getByReference(UUID workspaceId, String reference);
}
