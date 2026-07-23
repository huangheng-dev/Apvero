package io.apvero.platform.knowledge;

import java.util.List;
import java.util.UUID;

public interface KnowledgeIngestionJobCatalog {
    List<KnowledgeIngestionJob> list(
            UUID workspaceId, UUID knowledgeBaseId, KnowledgeIngestionJob.Status status);

    KnowledgeIngestionJob get(UUID workspaceId, UUID jobId);

    KnowledgeIngestionJob retry(UUID workspaceId, UUID jobId, KnowledgeCommandContext context);

    KnowledgeIngestionJob cancel(UUID workspaceId, UUID jobId, KnowledgeCommandContext context);
}
