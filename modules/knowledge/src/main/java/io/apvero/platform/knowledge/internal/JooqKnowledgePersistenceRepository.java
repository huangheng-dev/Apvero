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
