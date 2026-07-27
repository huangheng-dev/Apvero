package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildSourceCandidateRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
    public Optional<IndexRow> lockIndex(WorkspaceScope scope, UUID indexId) {
        return sql.fetchOptional(INDEX_SELECT
                        + " where tenant_id = ? and workspace_id = ? and id = ? for update",
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
    public Optional<BuildRow> findBuildByIndexAndVersion(
            WorkspaceScope scope, UUID indexId, String requestedVersion) {
        return sql.fetchOptional(BUILD_SELECT
                        + """
                         where tenant_id = ? and workspace_id = ?
                           and knowledge_index_id = ? and requested_version = ?
                         """,
                        scope.tenantId(), scope.workspaceId(), indexId, requestedVersion)
                .map(this::mapBuild);
    }

    @Override
    public List<BuildRow> listBuilds(WorkspaceScope scope, UUID indexId) {
        return sql.fetch(BUILD_SELECT
                        + """
                         where tenant_id = ? and workspace_id = ? and knowledge_index_id = ?
                         order by created_at desc, id desc
                         """,
                        scope.tenantId(), scope.workspaceId(), indexId)
                .map(this::mapBuild);
    }

    @Override
    public Optional<BuildRow> lockBuild(WorkspaceScope scope, UUID buildId) {
        return sql.fetchOptional(BUILD_SELECT
                        + " where tenant_id = ? and workspace_id = ? and id = ? for update",
                        scope.tenantId(), scope.workspaceId(), buildId)
                .map(this::mapBuild);
    }

    @Override
    public Optional<BuildRow> retryFailedBuild(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            OffsetDateTime retriedAt) {
        int changed = sql.execute("""
                update knowledge_index_build
                set status = 'RETRY_WAIT',
                    attempt_count = 0,
                    retryable = true,
                    next_attempt_at = ?,
                    lease_owner = null,
                    lease_until = null,
                    lock_version = lock_version + 1,
                    cancellation_requested = false,
                    error_code = null,
                    error_category = null,
                    reconciliation_required = false,
                    failure_metadata = '{}'::jsonb,
                    completed_at = null,
                    updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ?
                  and lock_version = ? and status = 'FAILED' and retryable = true
                """, timestamp(retriedAt), timestamp(retriedAt),
                scope.tenantId(), scope.workspaceId(), buildId, expectedVersion);
        return changed == 1 ? findBuild(scope, buildId) : Optional.empty();
    }

    @Override
    public Optional<BuildRow> cancelWaitingBuild(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            OffsetDateTime cancelledAt) {
        int changed = sql.execute("""
                update knowledge_index_build
                set status = 'CANCELLED',
                    retryable = false,
                    next_attempt_at = null,
                    lock_version = lock_version + 1,
                    cancellation_requested = true,
                    error_code = null,
                    error_category = null,
                    reconciliation_required = false,
                    failure_metadata = '{}'::jsonb,
                    completed_at = ?,
                    updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ?
                  and lock_version = ? and status in ('QUEUED', 'RETRY_WAIT')
                  and lease_owner is null and lease_until is null
                """, timestamp(cancelledAt), timestamp(cancelledAt),
                scope.tenantId(), scope.workspaceId(), buildId, expectedVersion);
        return changed == 1 ? findBuild(scope, buildId) : Optional.empty();
    }

    @Override
    public List<BuildRow> claimBuilds(
            WorkspaceScope scope,
            String leaseOwner,
            Duration leaseDuration,
            int limit) {
        List<UUID> claimedIds = sql.fetch("""
                        with lease_clock as (
                            select transaction_timestamp() as claimed_at
                        ),
                        candidates as (
                            select build.id
                            from knowledge_index_build build
                            cross join lease_clock
                            where build.tenant_id = ?
                              and build.workspace_id = ?
                              and (
                                  (build.status in ('QUEUED', 'RETRY_WAIT')
                                      and build.attempt_count < build.maximum_attempts
                                      and build.lease_owner is null
                                      and build.lease_until is null
                                      and (build.status = 'QUEUED'
                                          or build.next_attempt_at <= lease_clock.claimed_at))
                                  or
                                  (build.status in ('EMBEDDING', 'INDEXING', 'VALIDATING')
                                      and (
                                          (build.lease_owner is null and build.lease_until is null)
                                          or build.lease_until <= lease_clock.claimed_at
                                      ))
                              )
                            order by build.next_attempt_at nulls first, build.created_at, build.id
                            for update of build skip locked
                            limit ?
                        )
                        update knowledge_index_build build
                        set status = case
                                when build.status in ('QUEUED', 'RETRY_WAIT') then build.current_step
                                else build.status
                            end,
                            attempt_count = case
                                when build.status in ('QUEUED', 'RETRY_WAIT')
                                    then build.attempt_count + 1
                                else build.attempt_count
                            end,
                            retryable = case
                                when build.status in ('QUEUED', 'RETRY_WAIT') then false
                                else build.retryable
                            end,
                            next_attempt_at = case
                                when build.status in ('QUEUED', 'RETRY_WAIT') then null
                                else build.next_attempt_at
                            end,
                            lease_owner = ?,
                            lease_until = transaction_timestamp()
                                + (?::bigint * interval '1 millisecond'),
                            error_code = case
                                when build.status in ('QUEUED', 'RETRY_WAIT') then null
                                else build.error_code
                            end,
                            error_category = case
                                when build.status in ('QUEUED', 'RETRY_WAIT') then null
                                else build.error_category
                            end,
                            reconciliation_required = case
                                when build.status in ('QUEUED', 'RETRY_WAIT') then false
                                else build.reconciliation_required
                            end,
                            failure_metadata = case
                                when build.status in ('QUEUED', 'RETRY_WAIT') then '{}'::jsonb
                                else build.failure_metadata
                            end,
                            started_at = coalesce(build.started_at, transaction_timestamp()),
                            lock_version = build.lock_version + 1,
                            updated_at = transaction_timestamp()
                        from candidates
                        where build.id = candidates.id
                        returning build.id
                        """,
                        scope.tenantId(),
                        scope.workspaceId(),
                        limit,
                        leaseOwner,
                        leaseDuration.toMillis())
                .getValues("id", UUID.class);
        return claimedIds.stream()
                .map(id -> findBuild(scope, id).orElseThrow())
                .sorted(java.util.Comparator.comparing(BuildRow::createdAt).thenComparing(BuildRow::id))
                .toList();
    }

    @Override
    public Optional<BuildRow> renewBuildLease(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner,
            BuildStatus expectedStatus,
            BuildStep expectedStep,
            Duration leaseDuration) {
        int changed = sql.execute("""
                update knowledge_index_build
                set lease_until = transaction_timestamp()
                        + (?::bigint * interval '1 millisecond'),
                    lock_version = lock_version + 1,
                    updated_at = transaction_timestamp()
                where tenant_id = ? and workspace_id = ? and id = ?
                  and lock_version = ? and lease_owner = ?
                  and status = ? and current_step = ?
                  and lease_until > transaction_timestamp()
                """,
                leaseDuration.toMillis(),
                scope.tenantId(),
                scope.workspaceId(),
                buildId,
                expectedVersion,
                expectedLeaseOwner,
                expectedStatus.name(),
                expectedStep.name());
        return changed == 1 ? findBuild(scope, buildId) : Optional.empty();
    }

    @Override
    public Optional<BuildRow> recordEmbeddingProgressAndRelease(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner,
            int embeddedEntryCount,
            int lastDurableChunkOrdinal) {
        int changed = sql.execute("""
                update knowledge_index_build
                set embedded_entry_count = ?,
                    last_durable_chunk_ordinal = ?,
                    lease_owner = null,
                    lease_until = null,
                    lock_version = lock_version + 1,
                    updated_at = transaction_timestamp()
                where tenant_id = ? and workspace_id = ? and id = ?
                  and lock_version = ? and lease_owner = ?
                  and status = 'EMBEDDING' and current_step = 'EMBEDDING'
                  and lease_until > transaction_timestamp()
                  and embedded_entry_count <= ?
                  and requested_chunk_count >= ?
                  and ? = ? - 1
                  and (last_durable_chunk_ordinal is null
                      or last_durable_chunk_ordinal <= ?)
                """,
                embeddedEntryCount,
                lastDurableChunkOrdinal,
                scope.tenantId(),
                scope.workspaceId(),
                buildId,
                expectedVersion,
                expectedLeaseOwner,
                embeddedEntryCount,
                embeddedEntryCount,
                lastDurableChunkOrdinal,
                embeddedEntryCount,
                lastDurableChunkOrdinal);
        return changed == 1 ? findBuild(scope, buildId) : Optional.empty();
    }

    @Override
    public Optional<BuildRow> advanceBuildToIndexingAndRelease(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner) {
        int changed = sql.execute("""
                update knowledge_index_build
                set status = 'INDEXING',
                    current_step = 'INDEXING',
                    lease_owner = null,
                    lease_until = null,
                    lock_version = lock_version + 1,
                    updated_at = transaction_timestamp()
                where tenant_id = ? and workspace_id = ? and id = ?
                  and lock_version = ? and lease_owner = ?
                  and status = 'EMBEDDING' and current_step = 'EMBEDDING'
                  and lease_until > transaction_timestamp()
                  and embedded_entry_count = requested_chunk_count
                """,
                scope.tenantId(),
                scope.workspaceId(),
                buildId,
                expectedVersion,
                expectedLeaseOwner);
        return changed == 1 ? findBuild(scope, buildId) : Optional.empty();
    }

    @Override
    public Optional<BuildRow> advanceBuildToValidatingAndRelease(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner,
            int validatedEntryCount,
            String validationDigest) {
        int changed = sql.execute("""
                update knowledge_index_build
                set status = 'VALIDATING',
                    current_step = 'VALIDATING',
                    validated_entry_count = ?,
                    validation_digest = ?,
                    lease_owner = null,
                    lease_until = null,
                    lock_version = lock_version + 1,
                    updated_at = transaction_timestamp()
                where tenant_id = ? and workspace_id = ? and id = ?
                  and lock_version = ? and lease_owner = ?
                  and status = 'INDEXING' and current_step = 'INDEXING'
                  and lease_until > transaction_timestamp()
                  and embedded_entry_count = requested_chunk_count
                  and ? = requested_chunk_count
                """,
                validatedEntryCount,
                validationDigest,
                scope.tenantId(),
                scope.workspaceId(),
                buildId,
                expectedVersion,
                expectedLeaseOwner,
                validatedEntryCount);
        return changed == 1 ? findBuild(scope, buildId) : Optional.empty();
    }

    @Override
    public Optional<BuildRow> failLeasedBuild(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner,
            BuildStatus expectedStatus,
            BuildStep expectedStep,
            BuildStatus failureStatus,
            boolean retryable,
            Duration retryDelay,
            String errorCode,
            String errorCategory,
            boolean reconciliationRequired) {
        if (failureStatus != BuildStatus.RETRY_WAIT && failureStatus != BuildStatus.FAILED) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_INDEX_BUILD_FAILURE_STATUS_INVALID");
        }
        Long retryDelayMillis = retryDelay == null ? null : retryDelay.toMillis();
        int changed = sql.execute("""
                update knowledge_index_build
                set status = ?,
                    retryable = ?,
                    next_attempt_at = case
                        when ? = 'RETRY_WAIT' then transaction_timestamp()
                            + (?::bigint * interval '1 millisecond')
                        else null
                    end,
                    lease_owner = null,
                    lease_until = null,
                    error_code = ?,
                    error_category = ?,
                    reconciliation_required = ?,
                    failure_metadata = '{}'::jsonb,
                    completed_at = case
                        when ? = 'FAILED' then transaction_timestamp()
                        else null
                    end,
                    lock_version = lock_version + 1,
                    updated_at = transaction_timestamp()
                where tenant_id = ? and workspace_id = ? and id = ?
                  and lock_version = ? and lease_owner = ?
                  and status = ? and current_step = ?
                  and status in ('EMBEDDING', 'INDEXING', 'VALIDATING')
                  and lease_until > transaction_timestamp()
                """,
                failureStatus.name(),
                retryable,
                failureStatus.name(),
                retryDelayMillis,
                errorCode,
                errorCategory,
                reconciliationRequired,
                failureStatus.name(),
                scope.tenantId(),
                scope.workspaceId(),
                buildId,
                expectedVersion,
                expectedLeaseOwner,
                expectedStatus.name(),
                expectedStep.name());
        return changed == 1 ? findBuild(scope, buildId) : Optional.empty();
    }

    @Override
    public List<BuildSourceCandidateRow> listBuildSourceCandidates(
            WorkspaceScope scope,
            UUID knowledgeBaseId,
            List<UUID> sourceRevisionIds) {
        if (sourceRevisionIds.isEmpty()) {
            return List.of();
        }
        String placeholders = sourceRevisionIds.stream()
                .map(ignored -> "?")
                .collect(Collectors.joining(", "));
        String statement = """
                select source.id as source_id,
                    revision.id as source_revision_id,
                    revision.content_digest as source_content_digest,
                    min(document.parser_version) as parser_version,
                    min(chunk.chunker_version) as chunker_version,
                    count(distinct document.id) as document_count,
                    count(chunk.id) as chunk_count
                from knowledge_source source
                join knowledge_source_revision revision
                  on revision.source_id = source.id
                 and revision.tenant_id = source.tenant_id
                 and revision.workspace_id = source.workspace_id
                join knowledge_document document
                  on document.source_revision_id = revision.id
                 and document.tenant_id = revision.tenant_id
                 and document.workspace_id = revision.workspace_id
                join knowledge_chunk chunk
                  on chunk.document_id = document.id
                 and chunk.source_revision_id = revision.id
                 and chunk.tenant_id = revision.tenant_id
                 and chunk.workspace_id = revision.workspace_id
                where source.tenant_id = ?
                  and source.workspace_id = ?
                  and source.knowledge_base_id = ?
                  and source.status = 'ACTIVE'
                  and revision.snapshot_status = 'SNAPSHOTTED'
                  and revision.id in (%s)
                  and exists (
                      select 1
                      from knowledge_ingestion_job job
                      where job.tenant_id = revision.tenant_id
                        and job.workspace_id = revision.workspace_id
                        and job.source_id = source.id
                        and job.source_revision_id = revision.id
                        and job.status = 'READY'
                        and job.current_step = 'COMPLETE'
                  )
                group by source.id, revision.id, revision.content_digest
                having count(distinct document.parser_version) = 1
                   and count(distinct chunk.chunker_version) = 1
                   and count(distinct document.id) > 0
                   and count(chunk.id) > 0
                """.formatted(placeholders);
        List<Object> arguments = new ArrayList<>(3 + sourceRevisionIds.size());
        arguments.add(scope.tenantId());
        arguments.add(scope.workspaceId());
        arguments.add(knowledgeBaseId);
        arguments.addAll(sourceRevisionIds);
        return sql.fetch(statement, arguments.toArray())
                .map(record -> new BuildSourceCandidateRow(
                        uuid(record, "source_id"),
                        uuid(record, "source_revision_id"),
                        string(record, "source_content_digest"),
                        string(record, "parser_version"),
                        string(record, "chunker_version"),
                        Math.toIntExact(number(record, "document_count").longValue()),
                        Math.toIntExact(number(record, "chunk_count").longValue())));
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

    private static Number number(Record record, String field) {
        return record.get(field, Number.class);
    }

    private static OffsetDateTime time(Record record, String field) {
        return record.get(field, OffsetDateTime.class);
    }
}
