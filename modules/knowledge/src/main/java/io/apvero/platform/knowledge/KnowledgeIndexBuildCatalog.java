package io.apvero.platform.knowledge;

import java.util.List;
import java.util.UUID;

public interface KnowledgeIndexBuildCatalog {
    List<KnowledgeIndexBuild> list(UUID workspaceId, UUID indexId);

    KnowledgeIndexBuild get(UUID workspaceId, UUID buildId);

    KnowledgeIndexBuild create(
            UUID workspaceId,
            UUID indexId,
            CreateKnowledgeIndexBuildCommand command,
            KnowledgeCommandContext context);

    KnowledgeIndexBuild retry(
            UUID workspaceId,
            UUID buildId,
            KnowledgeCommandContext context);

    KnowledgeIndexBuild cancel(
            UUID workspaceId,
            UUID buildId,
            KnowledgeCommandContext context);
}
