package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ChunkRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.DocumentRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ErrorCategory;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobKind;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStep;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SnapshotStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceType;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SyncOutcome;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqKnowledgePersistenceRepository implements KnowledgePersistenceRepository {
    private static final String BASE_SELECT = """
            select id, tenant_id, workspace_id, slug, name, description, status, version, created_at, updated_at
            from knowledge_base
            """;
    private static final String SOURCE_SELECT = """
            select id, tenant_id, workspace_id, knowledge_base_id, name, source_type, status,
                   canonical_web_uri, latest_revision_number, latest_revision_id, version,
                   tombstoned_at, tombstoned_by, created_at, updated_at
            from knowledge_source
            """;
    private static final String REVISION_SELECT = """
            select id, tenant_id, workspace_id, source_id, revision, content_digest, media_type,
                   byte_size, original_filename, capture_metadata, snapshot_bytes, snapshot_status,
                   parser_version, chunker_version, created_at
            from knowledge_source_revision
            """;
    private static final String DOCUMENT_SELECT = """
            select id, tenant_id, workspace_id, source_revision_id, ordinal, title,
                   normalized_text_digest, parser_version, processing_profile, created_at
            from knowledge_document
            """;
    private static final String CHUNK_SELECT = """
            select id, tenant_id, workspace_id, source_revision_id, document_id, ordinal, text,
                   content_digest, start_offset, end_offset, page_number, heading, paragraph_number,
                   line_start, line_end, chunker_version, created_at
            from knowledge_chunk
            """;
    private static final String JOB_SELECT = """
            select id, tenant_id, workspace_id, knowledge_base_id, source_id, source_revision_id,
                   job_kind, status, current_step, sync_outcome, attempt_count, maximum_attempts,
                   next_attempt_at, lease_owner, lease_until, lock_version, idempotency_key,
                   retryable, error_code, error_category, failure_metadata, cancellation_requested,
                   started_at, completed_at, created_at, updated_at
            from knowledge_ingestion_job
            """;

    private final DSLContext sql;

    public JooqKnowledgePersistenceRepository(DSLContext sql) {
        this.sql = sql;
    }

    @Override
    public BaseRow insertBase(WorkspaceScope scope, BaseRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into knowledge_base(
                    id, tenant_id, workspace_id, slug, name, description, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.slug(), row.name(), row.description(),
                row.status().name(), row.version(), timestamp(row.createdAt()), timestamp(row.updatedAt()));
        return findBase(scope, row.id()).orElseThrow();
    }

    @Override
    public Optional<BaseRow> findBase(WorkspaceScope scope, UUID baseId) {
        return sql.fetchOptional(BASE_SELECT + " where tenant_id = ? and workspace_id = ? and id = ?",
                        scope.tenantId(), scope.workspaceId(), baseId)
                .map(this::mapBase);
    }

    @Override
    public Optional<BaseRow> findBaseBySlug(WorkspaceScope scope, String slug) {
        return sql.fetchOptional(BASE_SELECT + " where tenant_id = ? and workspace_id = ? and slug = ?",
                        scope.tenantId(), scope.workspaceId(), slug)
                .map(this::mapBase);
    }

    @Override
    public List<BaseRow> listBases(WorkspaceScope scope) {
        return sql.fetch(BASE_SELECT + " where tenant_id = ? and workspace_id = ? order by updated_at desc, id",
                        scope.tenantId(), scope.workspaceId())
                .map(this::mapBase);
    }

    @Override
    public SourceRow insertSource(WorkspaceScope scope, SourceRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into knowledge_source(
                    id, tenant_id, workspace_id, knowledge_base_id, name, source_type, status,
                    canonical_web_uri, latest_revision_number, latest_revision_id, version,
                    tombstoned_at, tombstoned_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.knowledgeBaseId(), row.name(),
                row.sourceType().name(), row.status().name(), row.canonicalWebUri(), row.latestRevisionNumber(),
                row.latestRevisionId(), row.version(), timestamp(row.tombstonedAt()), row.tombstonedBy(),
                timestamp(row.createdAt()), timestamp(row.updatedAt()));
        return findSource(scope, row.id()).orElseThrow();
    }

    @Override
    public Optional<SourceRow> findSource(WorkspaceScope scope, UUID sourceId) {
        return sql.fetchOptional(SOURCE_SELECT + " where tenant_id = ? and workspace_id = ? and id = ?",
                        scope.tenantId(), scope.workspaceId(), sourceId)
                .map(this::mapSource);
    }

    @Override
    public Optional<SourceRow> lockSource(WorkspaceScope scope, UUID sourceId) {
        return sql.fetchOptional(SOURCE_SELECT
                        + " where tenant_id = ? and workspace_id = ? and id = ? for update",
                        scope.tenantId(), scope.workspaceId(), sourceId)
                .map(this::mapSource);
    }

    @Override
    public List<SourceRow> listSources(WorkspaceScope scope, UUID knowledgeBaseId) {
        return sql.fetch(SOURCE_SELECT
                        + " where tenant_id = ? and workspace_id = ? and knowledge_base_id = ?"
                        + " order by updated_at desc, id",
                        scope.tenantId(), scope.workspaceId(), knowledgeBaseId)
                .map(this::mapSource);
    }

    @Override
    public Optional<SourceRow> updateSourceRevision(
            WorkspaceScope scope,
            UUID sourceId,
            long expectedVersion,
            int latestRevisionNumber,
            UUID latestRevisionId,
            OffsetDateTime updatedAt) {
        int changed = sql.execute("""
                update knowledge_source
                set latest_revision_number = ?, latest_revision_id = ?, version = version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ? and version = ? and status = 'ACTIVE'
                """, latestRevisionNumber, latestRevisionId, timestamp(updatedAt), scope.tenantId(),
                scope.workspaceId(), sourceId, expectedVersion);
        return changed == 1 ? findSource(scope, sourceId) : Optional.empty();
    }

    @Override
    public Optional<SourceRow> tombstoneSource(
            WorkspaceScope scope,
            UUID sourceId,
            long expectedVersion,
            OffsetDateTime tombstonedAt,
            String tombstonedBy) {
        int changed = sql.execute("""
                update knowledge_source
                set status = 'TOMBSTONED', tombstoned_at = ?, tombstoned_by = ?,
                    version = version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ? and version = ? and status = 'ACTIVE'
                """, timestamp(tombstonedAt), tombstonedBy, timestamp(tombstonedAt), scope.tenantId(),
                scope.workspaceId(), sourceId, expectedVersion);
        return changed == 1 ? findSource(scope, sourceId) : Optional.empty();
    }

    @Override
    public SourceRevisionRow insertRevision(WorkspaceScope scope, SourceRevisionRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into knowledge_source_revision(
                    id, tenant_id, workspace_id, source_id, revision, content_digest, media_type,
                    byte_size, original_filename, capture_metadata, snapshot_bytes, snapshot_status,
                    parser_version, chunker_version, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.sourceId(), row.revision(),
                row.contentDigest(), row.mediaType(), row.byteSize(), row.originalFilename(),
                JSONB.valueOf(row.captureMetadataJson()), row.snapshotBytes(), row.snapshotStatus().name(),
                row.parserVersion(), row.chunkerVersion(), timestamp(row.createdAt()));
        return findRevision(scope, row.id()).orElseThrow();
    }

    @Override
    public Optional<SourceRevisionRow> findRevision(WorkspaceScope scope, UUID revisionId) {
        return sql.fetchOptional(REVISION_SELECT + " where tenant_id = ? and workspace_id = ? and id = ?",
                        scope.tenantId(), scope.workspaceId(), revisionId)
                .map(this::mapRevision);
    }

    @Override
    public Optional<SourceRevisionRow> lockRevision(WorkspaceScope scope, UUID revisionId) {
        return sql.fetchOptional(REVISION_SELECT
                        + " where tenant_id = ? and workspace_id = ? and id = ? for update",
                        scope.tenantId(), scope.workspaceId(), revisionId)
                .map(this::mapRevision);
    }

    @Override
    public Optional<SourceRevisionRow> findLatestRevision(WorkspaceScope scope, UUID sourceId) {
        return sql.fetchOptional(REVISION_SELECT
                        + " where tenant_id = ? and workspace_id = ? and source_id = ?"
                        + " order by revision desc limit 1",
                        scope.tenantId(), scope.workspaceId(), sourceId)
                .map(this::mapRevision);
    }

    @Override
    public List<SourceRevisionRow> listRevisions(WorkspaceScope scope, UUID sourceId) {
        return sql.fetch(REVISION_SELECT
                        + " where tenant_id = ? and workspace_id = ? and source_id = ?"
                        + " order by revision desc, id",
                        scope.tenantId(), scope.workspaceId(), sourceId)
                .map(this::mapRevision);
    }

    @Override
    public DocumentRow insertDocument(WorkspaceScope scope, DocumentRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into knowledge_document(
                    id, tenant_id, workspace_id, source_revision_id, ordinal, title,
                    normalized_text_digest, parser_version, processing_profile, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.sourceRevisionId(), row.ordinal(),
                row.title(), row.normalizedTextDigest(), row.parserVersion(), row.processingProfile(),
                timestamp(row.createdAt()));
        return findDocument(scope, row.id()).orElseThrow();
    }

    @Override
    public Optional<DocumentRow> findDocument(WorkspaceScope scope, UUID documentId) {
        return sql.fetchOptional(DOCUMENT_SELECT + " where tenant_id = ? and workspace_id = ? and id = ?",
                        scope.tenantId(), scope.workspaceId(), documentId)
                .map(this::mapDocument);
    }

    @Override
    public List<DocumentRow> listDocuments(WorkspaceScope scope, UUID sourceRevisionId) {
        return sql.fetch(DOCUMENT_SELECT
                        + " where tenant_id = ? and workspace_id = ? and source_revision_id = ?"
                        + " order by ordinal, id",
                        scope.tenantId(), scope.workspaceId(), sourceRevisionId)
                .map(this::mapDocument);
    }

    @Override
    public ChunkRow insertChunk(WorkspaceScope scope, ChunkRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into knowledge_chunk(
                    id, tenant_id, workspace_id, source_revision_id, document_id, ordinal, text,
                    content_digest, start_offset, end_offset, page_number, heading, paragraph_number,
                    line_start, line_end, chunker_version, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.sourceRevisionId(), row.documentId(),
                row.ordinal(), row.text(), row.contentDigest(), row.startOffset(), row.endOffset(),
                row.pageNumber(), row.heading(), row.paragraphNumber(), row.lineStart(), row.lineEnd(),
                row.chunkerVersion(), timestamp(row.createdAt()));
        return findChunk(scope, row.id()).orElseThrow();
    }

    @Override
    public Optional<ChunkRow> findChunk(WorkspaceScope scope, UUID chunkId) {
        return sql.fetchOptional(CHUNK_SELECT + " where tenant_id = ? and workspace_id = ? and id = ?",
                        scope.tenantId(), scope.workspaceId(), chunkId)
                .map(this::mapChunk);
    }

    @Override
    public List<ChunkRow> listChunks(WorkspaceScope scope, UUID sourceRevisionId) {
        return sql.fetch(CHUNK_SELECT
                        + " where tenant_id = ? and workspace_id = ? and source_revision_id = ?"
                        + " order by document_id, ordinal, id",
                        scope.tenantId(), scope.workspaceId(), sourceRevisionId)
                .map(this::mapChunk);
    }

    @Override
    public IngestionJobRow insertJob(WorkspaceScope scope, IngestionJobRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into knowledge_ingestion_job(
                    id, tenant_id, workspace_id, knowledge_base_id, source_id, source_revision_id,
                    job_kind, status, current_step, sync_outcome, attempt_count, maximum_attempts,
                    next_attempt_at, lease_owner, lease_until, lock_version, idempotency_key,
                    retryable, error_code, error_category, failure_metadata, cancellation_requested,
                    started_at, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.knowledgeBaseId(), row.sourceId(),
                row.sourceRevisionId(), row.jobKind().name(), row.status().name(), row.currentStep().name(),
                enumName(row.syncOutcome()), row.attemptCount(), row.maximumAttempts(), timestamp(row.nextAttemptAt()),
                row.leaseOwner(), timestamp(row.leaseUntil()), row.lockVersion(), row.idempotencyKey(), row.retryable(),
                row.errorCode(), enumName(row.errorCategory()), JSONB.valueOf(row.failureMetadataJson()),
                row.cancellationRequested(), timestamp(row.startedAt()), timestamp(row.completedAt()),
                timestamp(row.createdAt()), timestamp(row.updatedAt()));
        return findJob(scope, row.id()).orElseThrow();
    }

    @Override
    public Optional<IngestionJobRow> findJob(WorkspaceScope scope, UUID jobId) {
        return sql.fetchOptional(JOB_SELECT + " where tenant_id = ? and workspace_id = ? and id = ?",
                        scope.tenantId(), scope.workspaceId(), jobId)
                .map(this::mapJob);
    }

    @Override
    public List<IngestionJobRow> listJobs(
            WorkspaceScope scope, UUID knowledgeBaseId, JobStatus status) {
        return sql.fetch(JOB_SELECT + """
                         where tenant_id = ? and workspace_id = ?
                           and (?::uuid is null or knowledge_base_id = ?::uuid)
                           and (?::varchar is null or status = ?::varchar)
                         order by created_at desc, id
                         """, scope.tenantId(), scope.workspaceId(), knowledgeBaseId, knowledgeBaseId,
                        enumName(status), enumName(status))
                .map(this::mapJob);
    }

    @Override
    public List<IngestionJobRow> claimJobs(
            WorkspaceScope scope,
            String leaseOwner,
            OffsetDateTime claimedAt,
            OffsetDateTime leaseUntil,
            int limit) {
        List<IngestionJobRow> candidates = sql.fetch(JOB_SELECT + """
                         where tenant_id = ? and workspace_id = ?
                           and attempt_count < maximum_attempts
                           and (
                                (status = 'QUEUED' and lease_owner is null)
                             or (status = 'RETRY_WAIT' and next_attempt_at <= ? and lease_owner is null)
                             or (status in ('SNAPSHOTTING', 'PARSING', 'CHUNKING')
                                 and (lease_until is null or lease_until <= ?))
                           )
                         order by created_at, id
                         for update skip locked
                         limit ?
                         """, scope.tenantId(), scope.workspaceId(), timestamp(claimedAt),
                        timestamp(claimedAt), limit)
                .map(this::mapJob);
        return candidates.stream().map(candidate -> {
            int changed = sql.execute("""
                    update knowledge_ingestion_job
                    set status = current_step, attempt_count = attempt_count + 1,
                        next_attempt_at = null, lease_owner = ?, lease_until = ?,
                        lock_version = lock_version + 1, retryable = false,
                        error_code = null, error_category = null, failure_metadata = '{}'::jsonb,
                        started_at = coalesce(started_at, ?), updated_at = ?
                    where tenant_id = ? and workspace_id = ? and id = ? and lock_version = ?
                    """, leaseOwner, timestamp(leaseUntil), timestamp(claimedAt), timestamp(claimedAt),
                    scope.tenantId(), scope.workspaceId(), candidate.id(), candidate.lockVersion());
            if (changed != 1) {
                throw new IllegalStateException("APVERO_KNOWLEDGE_JOB_CLAIM_CONFLICT");
            }
            return findJob(scope, candidate.id()).orElseThrow();
        }).toList();
    }

    @Override
    public List<IngestionJobRow> failExpiredExhaustedJobs(
            WorkspaceScope scope, OffsetDateTime failedAt) {
        List<IngestionJobRow> exhausted = sql.fetch(JOB_SELECT + """
                         where tenant_id = ? and workspace_id = ?
                           and status in ('SNAPSHOTTING', 'PARSING', 'CHUNKING')
                           and (lease_until is null or lease_until <= ?)
                           and attempt_count >= maximum_attempts
                         for update skip locked
                         """, scope.tenantId(), scope.workspaceId(), timestamp(failedAt))
                .map(this::mapJob);
        return exhausted.stream().map(job -> {
            int changed = sql.execute("""
                    update knowledge_ingestion_job
                    set status = 'FAILED', retryable = true, lease_owner = null, lease_until = null,
                        error_code = 'APVERO_KNOWLEDGE_RETRY_EXHAUSTED', error_category = 'TRANSIENT',
                        failure_metadata = '{}'::jsonb, completed_at = ?,
                        lock_version = lock_version + 1, updated_at = ?
                    where tenant_id = ? and workspace_id = ? and id = ? and lock_version = ?
                    """, timestamp(failedAt), timestamp(failedAt), scope.tenantId(), scope.workspaceId(),
                    job.id(), job.lockVersion());
            if (changed != 1) {
                throw new IllegalStateException("APVERO_KNOWLEDGE_JOB_RECOVERY_CONFLICT");
            }
            return findJob(scope, job.id()).orElseThrow();
        }).toList();
    }

    @Override
    public boolean hasActiveWebSnapshotJob(WorkspaceScope scope, UUID sourceId) {
        return Boolean.TRUE.equals(sql.fetchValue("""
                select exists (
                    select 1
                from knowledge_ingestion_job
                where tenant_id = ? and workspace_id = ? and source_id = ?
                  and current_step = 'SNAPSHOTTING'
                  and status in ('QUEUED', 'SNAPSHOTTING', 'RETRY_WAIT')
                )
                """, scope.tenantId(), scope.workspaceId(), sourceId));
    }

    @Override
    public Optional<IngestionJobRow> lockJob(WorkspaceScope scope, UUID jobId) {
        return sql.fetchOptional(JOB_SELECT
                        + " where tenant_id = ? and workspace_id = ? and id = ? for update",
                        scope.tenantId(), scope.workspaceId(), jobId)
                .map(this::mapJob);
    }

    @Override
    public Optional<IngestionJobRow> advanceWebJobAfterChangedSnapshot(
            WorkspaceScope scope,
            UUID jobId,
            long expectedVersion,
            String expectedLeaseOwner,
            UUID sourceRevisionId,
            OffsetDateTime updatedAt) {
        int changed = sql.execute("""
                update knowledge_ingestion_job
                set source_revision_id = ?, status = 'QUEUED', current_step = 'PARSING',
                    sync_outcome = 'CHANGED', attempt_count = 0, lease_owner = null, lease_until = null,
                    lock_version = lock_version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ? and lock_version = ?
                  and status in ('QUEUED', 'SNAPSHOTTING') and current_step = 'SNAPSHOTTING'
                  and job_kind in ('CREATE_SOURCE', 'SYNCHRONIZE_SOURCE')
                  and ((? and lease_owner is null) or (not ? and lease_owner = ?))
                  and (? or lease_until > ?)
                """, sourceRevisionId, timestamp(updatedAt), scope.tenantId(), scope.workspaceId(), jobId,
                expectedVersion, expectedLeaseOwner == null, expectedLeaseOwner == null, expectedLeaseOwner,
                expectedLeaseOwner == null, timestamp(updatedAt));
        return changed == 1 ? findJob(scope, jobId) : Optional.empty();
    }

    @Override
    public Optional<IngestionJobRow> completeWebJobWithoutChange(
            WorkspaceScope scope,
            UUID jobId,
            long expectedVersion,
            String expectedLeaseOwner,
            UUID sourceRevisionId,
            OffsetDateTime completedAt) {
        int changed = sql.execute("""
                update knowledge_ingestion_job
                set source_revision_id = ?, status = 'READY', current_step = 'COMPLETE',
                    sync_outcome = 'UNCHANGED', retryable = false, completed_at = ?,
                    lease_owner = null, lease_until = null,
                    lock_version = lock_version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ? and lock_version = ?
                  and status in ('QUEUED', 'SNAPSHOTTING') and current_step = 'SNAPSHOTTING'
                  and job_kind = 'SYNCHRONIZE_SOURCE'
                  and ((? and lease_owner is null) or (not ? and lease_owner = ?))
                  and (? or lease_until > ?)
                """, sourceRevisionId, timestamp(completedAt), timestamp(completedAt), scope.tenantId(),
                scope.workspaceId(), jobId, expectedVersion, expectedLeaseOwner == null,
                expectedLeaseOwner == null, expectedLeaseOwner, expectedLeaseOwner == null,
                timestamp(completedAt));
        return changed == 1 ? findJob(scope, jobId) : Optional.empty();
    }

    @Override
    public Optional<IngestionJobRow> advanceJobToChunking(
            WorkspaceScope scope,
            UUID jobId,
            long expectedVersion,
            String expectedLeaseOwner,
            OffsetDateTime updatedAt) {
        int changed = sql.execute("""
                update knowledge_ingestion_job
                set status = 'CHUNKING', current_step = 'CHUNKING',
                    lock_version = lock_version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ? and lock_version = ?
                  and status = 'PARSING' and current_step = 'PARSING' and lease_owner = ?
                  and lease_until > ?
                """, timestamp(updatedAt), scope.tenantId(), scope.workspaceId(), jobId,
                expectedVersion, expectedLeaseOwner, timestamp(updatedAt));
        return changed == 1 ? findJob(scope, jobId) : Optional.empty();
    }

    @Override
    public Optional<IngestionJobRow> completeJob(
            WorkspaceScope scope,
            UUID jobId,
            long expectedVersion,
            String expectedLeaseOwner,
            OffsetDateTime completedAt) {
        int changed = sql.execute("""
                update knowledge_ingestion_job
                set status = 'READY', current_step = 'COMPLETE', retryable = false,
                    next_attempt_at = null, lease_owner = null, lease_until = null,
                    error_code = null, error_category = null, failure_metadata = '{}'::jsonb,
                    completed_at = ?, lock_version = lock_version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ? and lock_version = ?
                  and status = 'CHUNKING' and current_step = 'CHUNKING' and lease_owner = ?
                  and lease_until > ?
                """, timestamp(completedAt), timestamp(completedAt), scope.tenantId(), scope.workspaceId(),
                jobId, expectedVersion, expectedLeaseOwner, timestamp(completedAt));
        return changed == 1 ? findJob(scope, jobId) : Optional.empty();
    }

    @Override
    public Optional<IngestionJobRow> failJob(
            WorkspaceScope scope,
            UUID jobId,
            long expectedVersion,
            String expectedLeaseOwner,
            JobStatus status,
            boolean retryable,
            OffsetDateTime nextAttemptAt,
            String errorCode,
            ErrorCategory errorCategory,
            OffsetDateTime failedAt) {
        if (status != JobStatus.RETRY_WAIT && status != JobStatus.FAILED) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_JOB_FAILURE_STATUS_INVALID");
        }
        OffsetDateTime completedAt = status == JobStatus.FAILED ? failedAt : null;
        int changed = sql.execute("""
                update knowledge_ingestion_job
                set status = ?, retryable = ?, next_attempt_at = ?,
                    lease_owner = null, lease_until = null, error_code = ?, error_category = ?,
                    failure_metadata = '{}'::jsonb, completed_at = ?,
                    lock_version = lock_version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ? and lock_version = ?
                  and status in ('SNAPSHOTTING', 'PARSING', 'CHUNKING') and lease_owner = ?
                  and lease_until > ?
                """, status.name(), retryable, timestamp(nextAttemptAt), errorCode, errorCategory.name(),
                timestamp(completedAt), timestamp(failedAt), scope.tenantId(), scope.workspaceId(), jobId,
                expectedVersion, expectedLeaseOwner, timestamp(failedAt));
        return changed == 1 ? findJob(scope, jobId) : Optional.empty();
    }

    @Override
    public Optional<IngestionJobRow> retryFailedJob(
            WorkspaceScope scope, UUID jobId, long expectedVersion, OffsetDateTime updatedAt) {
        int changed = sql.execute("""
                update knowledge_ingestion_job
                set status = 'QUEUED', attempt_count = 0, retryable = false,
                    error_code = null, error_category = null, failure_metadata = '{}'::jsonb,
                    cancellation_requested = false, completed_at = null,
                    lock_version = lock_version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ? and lock_version = ?
                  and status = 'FAILED' and retryable = true and lease_owner is null
                """, timestamp(updatedAt), scope.tenantId(), scope.workspaceId(), jobId, expectedVersion);
        return changed == 1 ? findJob(scope, jobId) : Optional.empty();
    }

    @Override
    public Optional<IngestionJobRow> cancelWaitingJob(
            WorkspaceScope scope, UUID jobId, long expectedVersion, OffsetDateTime cancelledAt) {
        int changed = sql.execute("""
                update knowledge_ingestion_job
                set status = 'CANCELLED', retryable = false, next_attempt_at = null,
                    cancellation_requested = true, completed_at = ?,
                    lock_version = lock_version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ? and lock_version = ?
                  and status in ('QUEUED', 'RETRY_WAIT') and lease_owner is null
                """, timestamp(cancelledAt), timestamp(cancelledAt), scope.tenantId(), scope.workspaceId(),
                jobId, expectedVersion);
        return changed == 1 ? findJob(scope, jobId) : Optional.empty();
    }

    private BaseRow mapBase(Record record) {
        return new BaseRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                string(record, "slug"), string(record, "name"), string(record, "description"),
                BaseStatus.valueOf(string(record, "status")), longValue(record, "version"),
                time(record, "created_at"), time(record, "updated_at"));
    }

    private SourceRow mapSource(Record record) {
        return new SourceRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                uuid(record, "knowledge_base_id"), string(record, "name"),
                SourceType.valueOf(string(record, "source_type")),
                SourceStatus.valueOf(string(record, "status")), string(record, "canonical_web_uri"),
                integer(record, "latest_revision_number"), uuid(record, "latest_revision_id"),
                longValue(record, "version"), time(record, "tombstoned_at"), string(record, "tombstoned_by"),
                time(record, "created_at"), time(record, "updated_at"));
    }

    private SourceRevisionRow mapRevision(Record record) {
        JSONB metadata = record.get("capture_metadata", JSONB.class);
        return new SourceRevisionRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                uuid(record, "source_id"), integer(record, "revision"), string(record, "content_digest"),
                string(record, "media_type"), longValue(record, "byte_size"), string(record, "original_filename"),
                metadata.data(), record.get("snapshot_bytes", byte[].class),
                SnapshotStatus.valueOf(string(record, "snapshot_status")), string(record, "parser_version"),
                string(record, "chunker_version"), time(record, "created_at"));
    }

    private DocumentRow mapDocument(Record record) {
        return new DocumentRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                uuid(record, "source_revision_id"), integer(record, "ordinal"), string(record, "title"),
                string(record, "normalized_text_digest"), string(record, "parser_version"),
                string(record, "processing_profile"), time(record, "created_at"));
    }

    private ChunkRow mapChunk(Record record) {
        return new ChunkRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                uuid(record, "source_revision_id"), uuid(record, "document_id"), integer(record, "ordinal"),
                string(record, "text"), string(record, "content_digest"), integer(record, "start_offset"),
                integer(record, "end_offset"), integer(record, "page_number"), string(record, "heading"),
                integer(record, "paragraph_number"), integer(record, "line_start"), integer(record, "line_end"),
                string(record, "chunker_version"), time(record, "created_at"));
    }

    private IngestionJobRow mapJob(Record record) {
        JSONB failureMetadata = record.get("failure_metadata", JSONB.class);
        String syncOutcome = string(record, "sync_outcome");
        String errorCategory = string(record, "error_category");
        return new IngestionJobRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                uuid(record, "knowledge_base_id"), uuid(record, "source_id"),
                uuid(record, "source_revision_id"), JobKind.valueOf(string(record, "job_kind")),
                JobStatus.valueOf(string(record, "status")), JobStep.valueOf(string(record, "current_step")),
                syncOutcome == null ? null : SyncOutcome.valueOf(syncOutcome), integer(record, "attempt_count"),
                integer(record, "maximum_attempts"), time(record, "next_attempt_at"),
                string(record, "lease_owner"), time(record, "lease_until"), longValue(record, "lock_version"),
                string(record, "idempotency_key"), Boolean.TRUE.equals(record.get("retryable", Boolean.class)),
                string(record, "error_code"), errorCategory == null ? null : ErrorCategory.valueOf(errorCategory),
                failureMetadata.data(), Boolean.TRUE.equals(record.get("cancellation_requested", Boolean.class)),
                time(record, "started_at"), time(record, "completed_at"),
                time(record, "created_at"), time(record, "updated_at"));
    }

    private static void requireScope(WorkspaceScope scope, UUID tenantId, UUID workspaceId) {
        if (!scope.tenantId().equals(tenantId) || !scope.workspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_SCOPE_MISMATCH");
        }
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static UUID uuid(Record record, String field) {
        return record.get(field, UUID.class);
    }

    private static String string(Record record, String field) {
        return record.get(field, String.class);
    }

    private static Integer integer(Record record, String field) {
        return record.get(field, Integer.class);
    }

    private static Long longValue(Record record, String field) {
        return record.get(field, Long.class);
    }

    private static OffsetDateTime time(Record record, String field) {
        return record.get(field, OffsetDateTime.class);
    }
}
