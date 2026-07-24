package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
class JooqKnowledgeIndexPersistenceRepository implements KnowledgeIndexPersistenceRepository {
    private static final String POLICY_SELECT = """
            select id, tenant_id, workspace_id, slug, version,
                retrieval_algorithm_version, token_estimator_version,
                retention_policy_version_at_publish, top_k, maximum_context_input_units,
                minimum_score, overlap_behavior, no_evidence_behavior, policy_digest,
                created_by, created_at
            from retrieval_policy_version
            """;
    private static final String INDEX_SELECT = """
            select id, tenant_id, workspace_id, knowledge_base_id, slug, name, status,
                metadata_version, version_count, latest_ready_version_id, created_at, updated_at
            from knowledge_index
            """;
    private static final String BUILD_SELECT = """
            select id, tenant_id, workspace_id, knowledge_index_id, knowledge_base_id,
                requested_version, embedding_route_id, embedding_route_reference,
                vector_dimension, maximum_input_tokens, maximum_batch_size, normalization,
                request_digest, source_set_digest, requested_source_count, requested_chunk_count,
                status, current_step, attempt_count, maximum_attempts, retryable,
                next_attempt_at, lease_owner, lease_until, lock_version, cancellation_requested,
                embedded_entry_count, validated_entry_count, last_durable_chunk_ordinal,
                validation_digest, artifact_digest, published_version_id, error_code,
                error_category, reconciliation_required, failure_metadata,
                started_at, completed_at, created_at, updated_at
            from knowledge_index_build
            """;
    private static final String REVISION_SELECT = """
            select id, tenant_id, workspace_id, knowledge_index_build_id, knowledge_index_id,
                knowledge_base_id, source_id, source_revision_id, source_content_digest,
                parser_version, chunker_version, source_set_ordinal, created_at
            from knowledge_index_build_revision
            """;
    private static final String ENTRY_SELECT = """
            select id, tenant_id, workspace_id, knowledge_index_build_id, knowledge_index_id,
                knowledge_base_id, source_id, source_revision_id, document_id, chunk_id,
                entry_ordinal, embedding::text as embedding_text, vector_dimension,
                vector_digest, normalized_input_digest, batch_ordinal, embedding_route_id,
                embedding_route_reference, created_at
            from knowledge_index_entry
            """;
    private static final String VERSION_SELECT = """
            select id, tenant_id, workspace_id, knowledge_index_id, knowledge_index_build_id,
                version, reference, embedding_route_id, embedding_route_reference,
                vector_dimension, source_count, chunk_count, artifact_digest, status, published_at
            from knowledge_index_version
            """;

    private final DSLContext sql;

    JooqKnowledgeIndexPersistenceRepository(DSLContext sql) {
        this.sql = sql;
    }

    @Override
    public RetrievalPolicyRow insertPolicy(WorkspaceScope scope, RetrievalPolicyRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into retrieval_policy_version(
                    id, tenant_id, workspace_id, slug, version,
                    retrieval_algorithm_version, token_estimator_version,
                    retention_policy_version_at_publish, top_k, maximum_context_input_units,
                    minimum_score, overlap_behavior, no_evidence_behavior, policy_digest,
                    created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.slug(), row.version(),
                row.retrievalAlgorithmVersion(), row.tokenEstimatorVersion(),
                row.retentionPolicyVersionAtPublish(), row.topK(), row.maximumContextInputUnits(),
                row.minimumScore(), row.overlapBehavior(), row.noEvidenceBehavior(),
                row.policyDigest(), row.createdBy(), timestamp(row.createdAt()));
        return findPolicy(scope, row.id()).orElseThrow();
    }

    @Override
    public Optional<RetrievalPolicyRow> findPolicy(WorkspaceScope scope, UUID policyId) {
        return sql.fetchOptional(POLICY_SELECT
                        + " where tenant_id = ? and workspace_id = ? and id = ?",
                        scope.tenantId(), scope.workspaceId(), policyId)
                .map(this::mapPolicy);
    }

    @Override
    public IndexRow insertIndex(WorkspaceScope scope, IndexRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into knowledge_index(
                    id, tenant_id, workspace_id, knowledge_base_id, slug, name, status,
                    metadata_version, version_count, latest_ready_version_id, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.knowledgeBaseId(),
                row.slug(), row.name(), row.status().name(), row.metadataVersion(),
                row.versionCount(), row.latestReadyVersionId(), timestamp(row.createdAt()),
                timestamp(row.updatedAt()));
        return findIndex(scope, row.id()).orElseThrow();
    }

    @Override
    public Optional<IndexRow> findIndex(WorkspaceScope scope, UUID indexId) {
        return sql.fetchOptional(INDEX_SELECT
                        + " where tenant_id = ? and workspace_id = ? and id = ?",
                        scope.tenantId(), scope.workspaceId(), indexId)
                .map(this::mapIndex);
    }

    @Override
    public BuildRow insertBuild(WorkspaceScope scope, BuildRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into knowledge_index_build(
                    id, tenant_id, workspace_id, knowledge_index_id, knowledge_base_id,
                    requested_version, embedding_route_id, embedding_route_reference,
                    vector_dimension, maximum_input_tokens, maximum_batch_size, normalization,
                    request_digest, source_set_digest, requested_source_count, requested_chunk_count,
                    status, current_step, attempt_count, maximum_attempts, retryable,
                    next_attempt_at, lease_owner, lease_until, lock_version, cancellation_requested,
                    embedded_entry_count, validated_entry_count, last_durable_chunk_ordinal,
                    validation_digest, artifact_digest, published_version_id, error_code,
                    error_category, reconciliation_required, failure_metadata,
                    started_at, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.knowledgeIndexId(),
                row.knowledgeBaseId(), row.requestedVersion(), row.embeddingRouteId(),
                row.embeddingRouteReference(), row.vectorDimension(), row.maximumInputTokens(),
                row.maximumBatchSize(), row.normalization(), row.requestDigest(),
                row.sourceSetDigest(), row.requestedSourceCount(), row.requestedChunkCount(),
                row.status().name(), row.currentStep().name(), row.attemptCount(),
                row.maximumAttempts(), row.retryable(), timestamp(row.nextAttemptAt()),
                row.leaseOwner(), timestamp(row.leaseUntil()), row.lockVersion(),
                row.cancellationRequested(), row.embeddedEntryCount(), row.validatedEntryCount(),
                row.lastDurableChunkOrdinal(), row.validationDigest(), row.artifactDigest(),
                row.publishedVersionId(), row.errorCode(), row.errorCategory(),
                row.reconciliationRequired(), row.failureMetadataJson(), timestamp(row.startedAt()),
                timestamp(row.completedAt()), timestamp(row.createdAt()), timestamp(row.updatedAt()));
        return findBuild(scope, row.id()).orElseThrow();
    }

    @Override
    public Optional<BuildRow> findBuild(WorkspaceScope scope, UUID buildId) {
        return sql.fetchOptional(BUILD_SELECT
                        + " where tenant_id = ? and workspace_id = ? and id = ?",
                        scope.tenantId(), scope.workspaceId(), buildId)
                .map(this::mapBuild);
    }

    @Override
    public BuildRevisionRow insertBuildRevision(WorkspaceScope scope, BuildRevisionRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into knowledge_index_build_revision(
                    id, tenant_id, workspace_id, knowledge_index_build_id, knowledge_index_id,
                    knowledge_base_id, source_id, source_revision_id, source_content_digest,
                    parser_version, chunker_version, source_set_ordinal, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.knowledgeIndexBuildId(),
                row.knowledgeIndexId(), row.knowledgeBaseId(), row.sourceId(),
                row.sourceRevisionId(), row.sourceContentDigest(), row.parserVersion(),
                row.chunkerVersion(), row.sourceSetOrdinal(), timestamp(row.createdAt()));
        return listBuildRevisions(scope, row.knowledgeIndexBuildId()).stream()
                .filter(saved -> saved.id().equals(row.id()))
                .findFirst()
                .orElseThrow();
    }

    @Override
    public List<BuildRevisionRow> listBuildRevisions(WorkspaceScope scope, UUID buildId) {
        return sql.fetch(REVISION_SELECT
                        + """
                         where tenant_id = ? and workspace_id = ? and knowledge_index_build_id = ?
                         order by source_set_ordinal, id
                         """, scope.tenantId(), scope.workspaceId(), buildId)
                .map(this::mapBuildRevision);
    }

    @Override
    public EntryRow insertEntry(WorkspaceScope scope, EntryRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        if (row.embedding().size() != row.vectorDimension()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_VECTOR_DIMENSION_MISMATCH");
        }
        sql.execute("""
                insert into knowledge_index_entry(
                    id, tenant_id, workspace_id, knowledge_index_build_id, knowledge_index_id,
                    knowledge_base_id, source_id, source_revision_id, document_id, chunk_id,
                    entry_ordinal, embedding, vector_dimension, vector_digest,
                    normalized_input_digest, batch_ordinal, embedding_route_id,
                    embedding_route_reference, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.knowledgeIndexBuildId(),
                row.knowledgeIndexId(), row.knowledgeBaseId(), row.sourceId(),
                row.sourceRevisionId(), row.documentId(), row.chunkId(), row.entryOrdinal(),
                vector(row.embedding()), row.vectorDimension(), row.vectorDigest(),
                row.normalizedInputDigest(), row.batchOrdinal(), row.embeddingRouteId(),
                row.embeddingRouteReference(), timestamp(row.createdAt()));
        return listEntries(scope, row.knowledgeIndexBuildId()).stream()
                .filter(saved -> saved.id().equals(row.id()))
                .findFirst()
                .orElseThrow();
    }

    @Override
    public List<EntryRow> listEntries(WorkspaceScope scope, UUID buildId) {
        return sql.fetch(ENTRY_SELECT
                        + """
                         where tenant_id = ? and workspace_id = ? and knowledge_index_build_id = ?
                         order by entry_ordinal, chunk_id
                         """, scope.tenantId(), scope.workspaceId(), buildId)
                .map(this::mapEntry);
    }

    @Override
    public VersionRow insertVersion(WorkspaceScope scope, VersionRow row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into knowledge_index_version(
                    id, tenant_id, workspace_id, knowledge_index_id, knowledge_index_build_id,
                    version, reference, embedding_route_id, embedding_route_reference,
                    vector_dimension, source_count, chunk_count, artifact_digest, status, published_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.knowledgeIndexId(),
                row.knowledgeIndexBuildId(), row.version(), row.reference(),
                row.embeddingRouteId(), row.embeddingRouteReference(), row.vectorDimension(),
                row.sourceCount(), row.chunkCount(), row.artifactDigest(), row.status(),
                timestamp(row.publishedAt()));
        return findVersion(scope, row.id()).orElseThrow();
    }

    @Override
    public Optional<VersionRow> findVersion(WorkspaceScope scope, UUID versionId) {
        return sql.fetchOptional(VERSION_SELECT
                        + " where tenant_id = ? and workspace_id = ? and id = ?",
                        scope.tenantId(), scope.workspaceId(), versionId)
                .map(this::mapVersion);
    }

    private RetrievalPolicyRow mapPolicy(Record record) {
        return new RetrievalPolicyRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                string(record, "slug"), string(record, "version"),
                string(record, "retrieval_algorithm_version"),
                string(record, "token_estimator_version"),
                longValue(record, "retention_policy_version_at_publish"),
                integer(record, "top_k"), integer(record, "maximum_context_input_units"),
                record.get("minimum_score", BigDecimal.class), string(record, "overlap_behavior"),
                string(record, "no_evidence_behavior"), string(record, "policy_digest"),
                string(record, "created_by"), time(record, "created_at"));
    }

    private IndexRow mapIndex(Record record) {
        return new IndexRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                uuid(record, "knowledge_base_id"), string(record, "slug"), string(record, "name"),
                IndexStatus.valueOf(string(record, "status")), longValue(record, "metadata_version"),
                integer(record, "version_count"), uuid(record, "latest_ready_version_id"),
                time(record, "created_at"), time(record, "updated_at"));
    }

    private BuildRow mapBuild(Record record) {
        JSONB failureMetadata = record.get("failure_metadata", JSONB.class);
        return new BuildRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                uuid(record, "knowledge_index_id"), uuid(record, "knowledge_base_id"),
                string(record, "requested_version"), uuid(record, "embedding_route_id"),
                string(record, "embedding_route_reference"), integer(record, "vector_dimension"),
                integer(record, "maximum_input_tokens"), integer(record, "maximum_batch_size"),
                string(record, "normalization"), string(record, "request_digest"),
                string(record, "source_set_digest"), integer(record, "requested_source_count"),
                integer(record, "requested_chunk_count"),
                BuildStatus.valueOf(string(record, "status")),
                BuildStep.valueOf(string(record, "current_step")),
                integer(record, "attempt_count"), integer(record, "maximum_attempts"),
                Boolean.TRUE.equals(record.get("retryable", Boolean.class)),
                time(record, "next_attempt_at"), string(record, "lease_owner"),
                time(record, "lease_until"), longValue(record, "lock_version"),
                Boolean.TRUE.equals(record.get("cancellation_requested", Boolean.class)),
                integer(record, "embedded_entry_count"),
                integer(record, "validated_entry_count"),
                integer(record, "last_durable_chunk_ordinal"),
                string(record, "validation_digest"), string(record, "artifact_digest"),
                uuid(record, "published_version_id"), string(record, "error_code"),
                string(record, "error_category"),
                Boolean.TRUE.equals(record.get("reconciliation_required", Boolean.class)),
                failureMetadata.data(), time(record, "started_at"), time(record, "completed_at"),
                time(record, "created_at"), time(record, "updated_at"));
    }

    private BuildRevisionRow mapBuildRevision(Record record) {
        return new BuildRevisionRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                uuid(record, "knowledge_index_build_id"), uuid(record, "knowledge_index_id"),
                uuid(record, "knowledge_base_id"), uuid(record, "source_id"),
                uuid(record, "source_revision_id"), string(record, "source_content_digest"),
                string(record, "parser_version"), string(record, "chunker_version"),
                integer(record, "source_set_ordinal"), time(record, "created_at"));
    }

    private EntryRow mapEntry(Record record) {
        return new EntryRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                uuid(record, "knowledge_index_build_id"), uuid(record, "knowledge_index_id"),
                uuid(record, "knowledge_base_id"), uuid(record, "source_id"),
                uuid(record, "source_revision_id"), uuid(record, "document_id"),
                uuid(record, "chunk_id"), integer(record, "entry_ordinal"),
                parseVector(string(record, "embedding_text")),
                integer(record, "vector_dimension"), string(record, "vector_digest"),
                string(record, "normalized_input_digest"), integer(record, "batch_ordinal"),
                uuid(record, "embedding_route_id"), string(record, "embedding_route_reference"),
                time(record, "created_at"));
    }

    private VersionRow mapVersion(Record record) {
        return new VersionRow(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                uuid(record, "knowledge_index_id"), uuid(record, "knowledge_index_build_id"),
                string(record, "version"), string(record, "reference"),
                uuid(record, "embedding_route_id"), string(record, "embedding_route_reference"),
                integer(record, "vector_dimension"), integer(record, "source_count"),
                integer(record, "chunk_count"), string(record, "artifact_digest"),
                string(record, "status"), time(record, "published_at"));
    }

    private static void requireScope(WorkspaceScope scope, UUID tenantId, UUID workspaceId) {
        if (!scope.tenantId().equals(tenantId) || !scope.workspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_SCOPE_MISMATCH");
        }
    }

    private static String vector(List<Float> values) {
        return values.stream()
                .map(value -> Float.toString(value))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static List<Float> parseVector(String value) {
        String body = value.substring(1, value.length() - 1);
        if (body.isBlank()) {
            return List.of();
        }
        return Arrays.stream(body.split(",")).map(Float::valueOf).toList();
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
