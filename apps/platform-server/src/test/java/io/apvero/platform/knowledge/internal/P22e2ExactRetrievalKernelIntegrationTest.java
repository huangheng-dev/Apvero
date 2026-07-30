package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.ExactRetrievalCandidate;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
class P22e2ExactRetrievalKernelIntegrationTest {
    private static final int DIMENSION = 3;

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p22e2_test")
            .withUsername("apvero")
            .withPassword("apvero")
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Autowired ExactKnowledgeRetrievalKernel kernel;
    @Autowired KnowledgeIndexPersistenceRepository repository;
    @Autowired JdbcTemplate sql;
    @Autowired DSLContext dsl;
    @Autowired TransactionTemplate transactions;

    @Test
    void ranksByDatabaseDistanceThenChunkIdAndAppliesThresholdAndExactTopK() {
        Fixture fixture = createPublishedFixture("rank");

        List<ExactRetrievalCandidate> topTwo = kernel.retrieve(
                fixture.scope(), fixture.versionId(), List.of(1.0F, 0.0F, 0.0F), 0.0, 2);

        assertThat(topTwo)
                .extracting(ExactRetrievalCandidate::chunkId)
                .containsExactly(fixture.chunkIds().get(0), fixture.chunkIds().get(1));
        assertThat(topTwo)
                .extracting(ExactRetrievalCandidate::rank)
                .containsExactly(1, 2);
        assertThat(topTwo)
                .extracting(candidate -> candidate.score().doubleValue())
                .allSatisfy(score -> assertThat(score).isEqualTo(1.0));

        List<ExactRetrievalCandidate> thresholded = kernel.retrieve(
                fixture.scope(), fixture.versionId(), List.of(1.0F, 0.0F, 0.0F), 0.75, 10);

        assertThat(thresholded)
                .extracting(ExactRetrievalCandidate::chunkId)
                .containsExactly(
                        fixture.chunkIds().get(0),
                        fixture.chunkIds().get(1),
                        fixture.chunkIds().get(2));
        assertThat(thresholded.get(2).score().doubleValue()).isCloseTo(
                0.8, org.assertj.core.data.Offset.offset(0.000001));

        assertThat(kernel.retrieve(
                        fixture.scope(),
                        fixture.versionId(),
                        List.of(1.0F, 0.0F, 0.0F),
                        0.0,
                        10))
                .hasSize(4)
                .last()
                .extracting(ExactRetrievalCandidate::score)
                .satisfies(score -> assertThat(score.doubleValue()).isEqualTo(0.0));
    }

    @Test
    void failsClosedAcrossTenantAndWorkspaceButPreservesPublishedTombstoneHistory() {
        Fixture fixture = createPublishedFixture("history");
        WorkspaceScope siblingWorkspace = createWorkspace(fixture.scope().tenantId(), "sibling");
        WorkspaceScope outsider = createScope("outsider");

        assertScopedNotFound(siblingWorkspace, fixture.versionId());
        assertScopedNotFound(outsider, fixture.versionId());

        OffsetDateTime tombstonedAt = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(sql.update("""
                update knowledge_source
                set status = 'TOMBSTONED', tombstoned_at = ?, tombstoned_by = 'p22e2-test',
                    version = version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ?
                """, tombstonedAt, tombstonedAt, fixture.scope().tenantId(),
                fixture.scope().workspaceId(), fixture.sourceId())).isEqualTo(1);

        assertThat(kernel.retrieve(
                        fixture.scope(),
                        fixture.versionId(),
                        List.of(1.0F, 0.0F, 0.0F),
                        0.75,
                        10))
                .extracting(ExactRetrievalCandidate::chunkId)
                .containsExactly(
                        fixture.chunkIds().get(0),
                        fixture.chunkIds().get(1),
                        fixture.chunkIds().get(2));
    }

    @Test
    void representativePlanUsesBoundedScopedAccessPath() {
        Fixture fixture = createPublishedFixture("plan");

        String plan = transactions.execute(ignored -> {
            sql.execute("set local enable_seqscan = off");
            return dsl.fetch(
                            "explain (analyze, buffers, format text) "
                                    + JooqKnowledgeIndexPersistenceRepository.EXACT_RETRIEVAL_SQL,
                            "[1.0,0.0,0.0]",
                            fixture.scope().tenantId(),
                            fixture.scope().workspaceId(),
                            fixture.versionId(),
                            0.0,
                            3)
                    .format();
        });

        assertThat(plan)
                .contains("Limit")
                .contains("Index")
                .contains("knowledge_index_version")
                .contains("knowledge_index_entry")
                .contains("rows=3");
    }

    private void assertScopedNotFound(WorkspaceScope scope, UUID versionId) {
        assertThatThrownBy(
                        () -> kernel.retrieve(
                                scope, versionId, List.of(1.0F, 0.0F, 0.0F), 0.0, 10))
                .isInstanceOf(KnowledgeException.class)
                .satisfies(error -> assertThat(((KnowledgeException) error).code())
                        .isEqualTo("APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND"));
    }

    private Fixture createPublishedFixture(String label) {
        WorkspaceScope scope = createScope(label);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID baseId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID routeId = createEmbeddingRoute(scope, label, suffix);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        sql.update("""
                insert into knowledge_base(
                    id, tenant_id, workspace_id, slug, name, description, status,
                    version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '', 'ACTIVE', 1, ?, ?)
                """, baseId, scope.tenantId(), scope.workspaceId(), "base-" + suffix,
                "Base " + label, now, now);
        sql.update("""
                insert into knowledge_source(
                    id, tenant_id, workspace_id, knowledge_base_id, name, source_type,
                    status, latest_revision_number, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'TEXT', 'ACTIVE', 0, 1, ?, ?)
                """, sourceId, scope.tenantId(), scope.workspaceId(), baseId,
                "Source " + label, now, now);
        sql.update("""
                insert into knowledge_source_revision(
                    id, tenant_id, workspace_id, source_id, revision, content_digest,
                    media_type, byte_size, capture_metadata, snapshot_bytes, snapshot_status,
                    parser_version, chunker_version, created_at)
                values (?, ?, ?, ?, 1, ?, 'text/plain', 5, '{}'::jsonb,
                    convert_to('hello', 'UTF8'), 'SNAPSHOTTED',
                    'apvero-text@1.0.0', 'apvero-boundary@1.0.0', ?)
                """, revisionId, scope.tenantId(), scope.workspaceId(), sourceId,
                digest('1'), now);
        sql.update("""
                insert into knowledge_document(
                    id, tenant_id, workspace_id, source_revision_id, ordinal, title,
                    normalized_text_digest, parser_version, processing_profile, created_at)
                values (?, ?, ?, ?, 0, 'Document', ?,
                    'apvero-text@1.0.0', 'apvero-default@1.0.0', ?)
                """, documentId, scope.tenantId(), scope.workspaceId(), revisionId,
                digest('2'), now);
        sql.update("""
                insert into knowledge_ingestion_job(
                    id, tenant_id, workspace_id, knowledge_base_id, source_id,
                    source_revision_id, job_kind, status, current_step, sync_outcome,
                    attempt_count, maximum_attempts, lock_version, idempotency_key,
                    retryable, failure_metadata, cancellation_requested,
                    started_at, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'CREATE_SOURCE', 'READY', 'COMPLETE', 'CHANGED',
                    1, 3, 1, ?, false, '{}'::jsonb, false, ?, ?, ?, ?)
                """, UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), baseId,
                sourceId, revisionId, "ready-" + suffix, now, now, now, now);

        long uuidPrefix = UUID.randomUUID().getMostSignificantBits();
        List<UUID> chunkIds = List.of(
                new UUID(uuidPrefix, 1L),
                new UUID(uuidPrefix, 2L),
                new UUID(uuidPrefix, 3L),
                new UUID(uuidPrefix, 4L));
        for (int ordinal = 0; ordinal < chunkIds.size(); ordinal++) {
            String text = "evidence-" + ordinal;
            sql.update("""
                    insert into knowledge_chunk(
                        id, tenant_id, workspace_id, source_revision_id, document_id,
                        ordinal, text, content_digest, start_offset, end_offset,
                        paragraph_number, line_start, line_end, chunker_version, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'apvero-boundary@1.0.0', ?)
                    """, chunkIds.get(ordinal), scope.tenantId(), scope.workspaceId(),
                    revisionId, documentId, ordinal, text, digest((char) ('3' + ordinal)),
                    ordinal * 20, ordinal * 20 + text.length(), ordinal + 1,
                    ordinal + 1, ordinal + 1, now);
        }

        IndexRow index = repository.insertIndex(scope, new IndexRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), baseId,
                "index-" + suffix, "Index " + label, IndexStatus.ACTIVE,
                1, 0, null, now, now));
        BuildRow queued = repository.insertBuild(scope, new BuildRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(),
                index.id(), baseId, "1.0.0", routeId, "route-" + suffix + "@1",
                DIMENSION, 8192, 64, "L2", digest('8'), digest('9'), 1, 4,
                BuildStatus.QUEUED, BuildStep.EMBEDDING, 0, 3, false,
                null, null, null, 1, false, 0, 0, null, null, null, null,
                null, null, false, "{}", null, null, now, now));
        repository.insertBuildRevision(scope, new BuildRevisionRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(),
                queued.id(), index.id(), baseId, sourceId, revisionId,
                digest('1'), "apvero-text@1.0.0", "apvero-boundary@1.0.0", 0, now));
        assertThat(sql.update("""
                update knowledge_index_build
                set status = 'EMBEDDING', started_at = ?,
                    lock_version = lock_version + 1, updated_at = ?
                where id = ?
                """, now, now, queued.id())).isEqualTo(1);

        List<List<Float>> vectors = List.of(
                List.of(1.0F, 0.0F, 0.0F),
                List.of(1.0F, 0.0F, 0.0F),
                List.of(0.8F, 0.6F, 0.0F),
                List.of(0.0F, 1.0F, 0.0F));
        for (int ordinal = vectors.size() - 1; ordinal >= 0; ordinal--) {
            repository.insertEntry(scope, new EntryRow(
                    UUID.randomUUID(), scope.tenantId(), scope.workspaceId(),
                    queued.id(), index.id(), baseId, sourceId, revisionId, documentId,
                    chunkIds.get(ordinal), ordinal, vectors.get(ordinal), DIMENSION,
                    digest((char) ('a' + ordinal)), digest('e'),
                    ordinal, routeId, "route-" + suffix + "@1", now));
        }

        UUID versionId = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> {
            sql.update("""
                    update knowledge_index_build
                    set status = 'INDEXING', current_step = 'INDEXING',
                        embedded_entry_count = 4, last_durable_chunk_ordinal = 3,
                        lock_version = lock_version + 1, updated_at = ?
                    where id = ?
                    """, now, queued.id());
            sql.update("""
                    update knowledge_index_build
                    set status = 'VALIDATING', current_step = 'VALIDATING',
                        validated_entry_count = 4, validation_digest = ?, artifact_digest = ?,
                        lock_version = lock_version + 1, updated_at = ?
                    where id = ?
                    """, digest('6'), digest('7'), now, queued.id());
            repository.insertVersion(scope, new VersionRow(
                    versionId, scope.tenantId(), scope.workspaceId(), index.id(), queued.id(),
                    "1.0.0", "index-" + suffix + "@1.0.0", routeId,
                    "route-" + suffix + "@1", DIMENSION, 1, 4,
                    digest('7'), "READY", now));
            sql.update("""
                    update knowledge_index_build
                    set status = 'READY', current_step = 'COMPLETE',
                        published_version_id = ?, completed_at = ?,
                        lock_version = lock_version + 1, updated_at = ?
                    where id = ?
                    """, versionId, now, now, queued.id());
            sql.update("""
                    update knowledge_index
                    set latest_ready_version_id = ?, version_count = 1,
                        metadata_version = metadata_version + 1, updated_at = ?
                    where id = ?
                    """, versionId, now, index.id());
        });
        return new Fixture(scope, sourceId, versionId, chunkIds);
    }

    private UUID createEmbeddingRoute(WorkspaceScope scope, String label, String suffix) {
        UUID providerId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.update("""
                insert into model_provider(
                    id, tenant_id, workspace_id, name, provider_type, base_url,
                    enabled, version, created_at, updated_at)
                values (?, ?, ?, ?, 'DETERMINISTIC_LOCAL', 'local://deterministic',
                    true, 1, ?, ?)
                """, providerId, scope.tenantId(), scope.workspaceId(),
                "Provider " + label, now, now);
        sql.update("""
                insert into model_definition(
                    id, tenant_id, workspace_id, provider_id, model_key, name,
                    capabilities, input_cost_micros_per_million,
                    output_cost_micros_per_million, enabled, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, '["EMBEDDING"]'::jsonb, 0, 0, true, ?, ?)
                """, modelId, scope.tenantId(), scope.workspaceId(), providerId,
                "model-" + suffix, "Model " + label, now, now);
        sql.update("""
                insert into model_route(
                    id, tenant_id, workspace_id, name, version, model_id, status,
                    timeout_ms, route_capability, embedding_dimension,
                    embedding_maximum_input_tokens, embedding_maximum_batch_size,
                    embedding_normalization, created_at)
                values (?, ?, ?, ?, 1, ?, 'PUBLISHED', 30000, 'EMBEDDING',
                    3, 8192, 64, 'L2', ?)
                """, routeId, scope.tenantId(), scope.workspaceId(),
                "route-" + suffix, modelId, now);
        return routeId;
    }

    private WorkspaceScope createScope(String label) {
        UUID tenantId = UUID.randomUUID();
        String suffix = tenantId.toString().replace("-", "").substring(0, 12);
        sql.update("insert into tenant(id, slug, name, created_at) values (?, ?, ?, now())",
                tenantId, "t-" + suffix, "Tenant " + label);
        return createWorkspace(tenantId, label);
    }

    private WorkspaceScope createWorkspace(UUID tenantId, String label) {
        UUID workspaceId = UUID.randomUUID();
        String suffix = workspaceId.toString().replace("-", "").substring(0, 12);
        sql.update("""
                insert into workspace(id, tenant_id, slug, name, created_at)
                values (?, ?, ?, ?, now())
                """, workspaceId, tenantId, "w-" + suffix, "Workspace " + label);
        return new WorkspaceScope(tenantId, workspaceId);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(
            WorkspaceScope scope,
            UUID sourceId,
            UUID versionId,
            List<UUID> chunkIds) {}
}
