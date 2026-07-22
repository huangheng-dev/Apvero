package io.apvero.platform.knowledge;

import java.util.List;
import java.util.UUID;

public interface KnowledgeSourceCatalog {
    List<KnowledgeSource> listSources(UUID workspaceId, UUID knowledgeBaseId);

    List<KnowledgeSourceRevision> listRevisions(UUID workspaceId, UUID sourceId);

    SourceIngestionReceipt createInline(
            UUID workspaceId,
            UUID knowledgeBaseId,
            CreateInlineKnowledgeSourceCommand command,
            KnowledgeCommandContext context);

    SourceIngestionReceipt createUpload(
            UUID workspaceId,
            UUID knowledgeBaseId,
            CreateUploadedKnowledgeSourceCommand command,
            KnowledgeCommandContext context);

    SourceIngestionReceipt createWeb(
            UUID workspaceId,
            UUID knowledgeBaseId,
            CreateWebKnowledgeSourceCommand command,
            KnowledgeCommandContext context);

    SourceRevisionReceipt addInlineRevision(
            UUID workspaceId,
            UUID sourceId,
            AddInlineKnowledgeSourceRevisionCommand command,
            KnowledgeCommandContext context);

    SourceRevisionReceipt addUploadRevision(
            UUID workspaceId,
            UUID sourceId,
            AddUploadedKnowledgeSourceRevisionCommand command,
            KnowledgeCommandContext context);

    SourceSyncReceipt synchronizeWeb(
            UUID workspaceId,
            UUID sourceId,
            KnowledgeCommandContext context);

    KnowledgeSourceSnapshot readRevisionContent(UUID workspaceId, UUID revisionId);

    void tombstone(UUID workspaceId, UUID sourceId, KnowledgeCommandContext context);
}
