package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ChunkRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.DocumentRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ErrorCategory;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;

interface KnowledgePersistenceRepository {
    BaseRow insertBase(WorkspaceScope scope, BaseRow row);

    Optional<BaseRow> findBase(WorkspaceScope scope, UUID baseId);

    Optional<BaseRow> findBaseBySlug(WorkspaceScope scope, String slug);

    List<BaseRow> listBases(WorkspaceScope scope);

    SourceRow insertSource(WorkspaceScope scope, SourceRow row);

    Optional<SourceRow> findSource(WorkspaceScope scope, UUID sourceId);

    Optional<SourceRow> lockSource(WorkspaceScope scope, UUID sourceId);

    List<SourceRow> listSources(WorkspaceScope scope, UUID knowledgeBaseId);

    Optional<SourceRow> updateSourceRevision(
            WorkspaceScope scope,
            UUID sourceId,
            long expectedVersion,
            int latestRevisionNumber,
            UUID latestRevisionId,
            OffsetDateTime updatedAt);

    Optional<SourceRow> tombstoneSource(
            WorkspaceScope scope,
            UUID sourceId,
            long expectedVersion,
            OffsetDateTime tombstonedAt,
            String tombstonedBy);

    SourceRevisionRow insertRevision(WorkspaceScope scope, SourceRevisionRow row);

    Optional<SourceRevisionRow> findRevision(WorkspaceScope scope, UUID revisionId);

    Optional<SourceRevisionRow> lockRevision(WorkspaceScope scope, UUID revisionId);

    Optional<SourceRevisionRow> findLatestRevision(WorkspaceScope scope, UUID sourceId);

    List<SourceRevisionRow> listRevisions(WorkspaceScope scope, UUID sourceId);

    DocumentRow insertDocument(WorkspaceScope scope, DocumentRow row);

    Optional<DocumentRow> findDocument(WorkspaceScope scope, UUID documentId);

    List<DocumentRow> listDocuments(WorkspaceScope scope, UUID sourceRevisionId);

    ChunkRow insertChunk(WorkspaceScope scope, ChunkRow row);

    Optional<ChunkRow> findChunk(WorkspaceScope scope, UUID chunkId);

    List<ChunkRow> listChunks(WorkspaceScope scope, UUID sourceRevisionId);

    IngestionJobRow insertJob(WorkspaceScope scope, IngestionJobRow row);

    Optional<IngestionJobRow> findJob(WorkspaceScope scope, UUID jobId);

    List<IngestionJobRow> listJobs(WorkspaceScope scope, UUID knowledgeBaseId, JobStatus status);

    List<IngestionJobRow> claimJobs(
            WorkspaceScope scope,
            String leaseOwner,
            OffsetDateTime claimedAt,
            OffsetDateTime leaseUntil,
            int limit);

    List<IngestionJobRow> failExpiredExhaustedJobs(WorkspaceScope scope, OffsetDateTime failedAt);

    boolean hasActiveWebSnapshotJob(WorkspaceScope scope, UUID sourceId);

    Optional<IngestionJobRow> lockJob(WorkspaceScope scope, UUID jobId);

    Optional<IngestionJobRow> advanceWebJobAfterChangedSnapshot(
            WorkspaceScope scope,
            UUID jobId,
            long expectedVersion,
            String expectedLeaseOwner,
            UUID sourceRevisionId,
            OffsetDateTime updatedAt);

    Optional<IngestionJobRow> completeWebJobWithoutChange(
            WorkspaceScope scope,
            UUID jobId,
            long expectedVersion,
            String expectedLeaseOwner,
            UUID sourceRevisionId,
            OffsetDateTime completedAt);

    Optional<IngestionJobRow> advanceJobToChunking(
            WorkspaceScope scope,
            UUID jobId,
            long expectedVersion,
            String expectedLeaseOwner,
            OffsetDateTime updatedAt);

    Optional<IngestionJobRow> completeJob(
            WorkspaceScope scope,
            UUID jobId,
            long expectedVersion,
            String expectedLeaseOwner,
            OffsetDateTime completedAt);

    Optional<IngestionJobRow> failJob(
            WorkspaceScope scope,
            UUID jobId,
            long expectedVersion,
            String expectedLeaseOwner,
            JobStatus status,
            boolean retryable,
            OffsetDateTime nextAttemptAt,
            String errorCode,
            ErrorCategory errorCategory,
            OffsetDateTime failedAt);

    Optional<IngestionJobRow> retryFailedJob(
            WorkspaceScope scope, UUID jobId, long expectedVersion, OffsetDateTime updatedAt);

    Optional<IngestionJobRow> cancelWaitingJob(
            WorkspaceScope scope, UUID jobId, long expectedVersion, OffsetDateTime cancelledAt);
}
