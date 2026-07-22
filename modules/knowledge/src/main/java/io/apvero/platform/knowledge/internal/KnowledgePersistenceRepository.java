package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ChunkRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.DocumentRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface KnowledgePersistenceRepository {
    BaseRow insertBase(WorkspaceScope scope, BaseRow row);

    Optional<BaseRow> findBase(WorkspaceScope scope, UUID baseId);

    List<BaseRow> listBases(WorkspaceScope scope);

    SourceRow insertSource(WorkspaceScope scope, SourceRow row);

    Optional<SourceRow> findSource(WorkspaceScope scope, UUID sourceId);

    SourceRevisionRow insertRevision(WorkspaceScope scope, SourceRevisionRow row);

    Optional<SourceRevisionRow> findRevision(WorkspaceScope scope, UUID revisionId);

    DocumentRow insertDocument(WorkspaceScope scope, DocumentRow row);

    Optional<DocumentRow> findDocument(WorkspaceScope scope, UUID documentId);

    ChunkRow insertChunk(WorkspaceScope scope, ChunkRow row);

    Optional<ChunkRow> findChunk(WorkspaceScope scope, UUID chunkId);

    IngestionJobRow insertJob(WorkspaceScope scope, IngestionJobRow row);

    Optional<IngestionJobRow> findJob(WorkspaceScope scope, UUID jobId);
}
