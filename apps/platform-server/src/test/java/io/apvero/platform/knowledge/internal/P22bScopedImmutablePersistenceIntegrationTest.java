package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
class P22bScopedImmutablePersistenceIntegrationTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p22b_test")
            .withUsername("apvero")
            .withPassword("apvero");

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

    @Autowired KnowledgeIndexPersistenceRepository repository;
    @Autowired KnowledgeEmbeddingEntryBatchWriter batchWriter;
    @Autowired JdbcTemplate sql;
    @Autowired TransactionTemplate transactions;

    @Test
    void cleanMigrationCreatesOnlyTheSevenApprovedScopedTablesAndProtectionTriggers() {
        assertThat(sql.queryForObject(
                "select count(*) from flyway_schema_history where version = '11' and success",
                Integer.class)).isEqualTo(1);

        assertThat(sql.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = current_schema()
                  and table_name in (
                    'retrieval_policy_version', 'knowledge_index', 'knowledge_index_build',
                    'knowledge_index_build_revision', 'knowledge_index_entry',
                    'knowledge_index_version', 'execution_reservation_component')
                """, Integer.class)).isEqualTo(7);

        assertThat(sql.queryForObject("""
                select count(*)
                from information_schema.table_constraints
                where table_schema = current_schema()
                  and table_name in (
                    'retrieval_policy_version', 'knowledge_index', 'knowledge_index_build',
                    'knowledge_index_build_revision', 'knowledge_index_entry',
                    'knowledge_index_version', 'execution_reservation_component')
                  and constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isGreaterThanOrEqualTo(15);

        assertThat(sql.queryForObject("""
                select count(distinct trigger_name)
                from information_schema.triggers
                where trigger_schema = current_schema()
                  and trigger_name in (
                    'retrieval_policy_version_is_insert_only',
                    'knowledge_index_build_revision_is_insert_only',
                    'knowledge_index_entry_is_insert_only',
                    'knowledge_index_version_is_insert_only',
                    'knowledge_index_entry_rejects_published_build',
                    'knowledge_index_build_preserves_durable_state',
                    'knowledge_index_build_validates_route_profile',
                    'knowledge_index_build_revision_validates_snapshot',
                    'knowledge_index_version_validates_publication',
                    'execution_reservation_component_transition_guard')
                """, Integer.class)).isEqualTo(10);

        assertThat(sql.queryForObject("""
                select count(*)
                from pg_indexes
                where schemaname = current_schema()
                  and indexname in (
                    'idx_knowledge_index_build_claim',
                    'idx_knowledge_index_entry_order',
                    'idx_execution_component_reconciliation')
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void upgradesARealV8SchemaThroughV9AndV10AndBackfillsExistingReservations() throws Exception {
        Assumptions.assumeTrue(
                POSTGRES.isRunning(), "V8 upgrade verification requires an isolated Testcontainer");
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway toV8 = flyway(schema, "8");
            assertThat(toV8.migrate().migrationsExecuted).isEqualTo(8);
            UUID reservationId = UUID.randomUUID();
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                connection.setSchema(schema);
                try (var statement = connection.prepareStatement("""
                        insert into execution_reservation(
                            id, tenant_id, workspace_id, application_id, model_route_id,
                            actor_id, trace_id, estimated_cost_micros, status, created_at)
                        values (
                            ?, '00000000-0000-0000-0000-000000000001'::uuid,
                            '00000000-0000-0000-0000-000000000101'::uuid,
                            '00000000-0000-0000-0000-000000001001'::uuid,
                            '00000000-0000-0000-0000-000000003201'::uuid,
                            'upgrade-test', ?, 0, 'RESERVED', now())
                        """)) {
                    statement.setObject(1, reservationId);
                    statement.setString(2, "upgrade-" + reservationId);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
            }

            Flyway toV10 = flyway(schema, "10");
            assertThat(toV10.migrate().migrationsExecuted).isEqualTo(2);
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                connection.setSchema(schema);
                try (var statement = connection.prepareStatement("""
                        select reservation.subject_type, reservation.subject_id,
                            reservation.application_id, route.route_capability
                        from execution_reservation reservation
                        join model_route route on route.id = reservation.model_route_id
                        where reservation.id = ?
                        """)) {
                    statement.setObject(1, reservationId);
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString("subject_type")).isEqualTo("APPLICATION_RUN");
                        assertThat(result.getObject("subject_id"))
                                .isEqualTo(result.getObject("application_id"));
                        assertThat(result.getString("route_capability")).isEqualTo("CHAT");
                    }
                }
            }
        } finally {
            sql.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void upgradesARealV10SchemaToV11WithoutAddingStatefulTables() {
        String schema = "upgrade_v11_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway toV10 = flyway(schema, "10");
            assertThat(toV10.migrate().migrationsExecuted).isEqualTo(10);
            int tableCountAtV10 = countTables(schema);

            Flyway toV11 = flyway(schema, "11");
            assertThat(toV11.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(countTables(schema)).isEqualTo(tableCountAtV10);
            assertThat(queryInSchema(schema, """
                    select count(*)
                    from information_schema.triggers
                    where trigger_schema = ?
                      and trigger_name = 'knowledge_index_version_validates_publication'
                    """)).isEqualTo(1);
        } finally {
            sql.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void databaseRejectsSkippedBuildTransitionsAndPartialPublication() {
        Fixture fixture = createFixture("v11-guards");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        IndexRow index = repository.insertIndex(fixture.scope(), new IndexRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                fixture.baseId(), "guard-index", "Guard Index", IndexStatus.ACTIVE,
                1, 0, null, now, now));
        BuildRow build = repository.insertBuild(fixture.scope(), queuedBuild(
                fixture, index.id(), "1.0.0", digest('1'), digest('2'), now));
        repository.insertBuildRevision(
                fixture.scope(), buildRevision(fixture, index.id(), build.id(), now));

        assertThatThrownBy(() -> sql.update("""
                update knowledge_index_build
                set status = 'VALIDATING', current_step = 'VALIDATING',
                    lock_version = lock_version + 1, updated_at = now()
                where id = ?
                """, build.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("illegal knowledge_index_build state transition");
        assertThatThrownBy(() -> sql.update("""
                update knowledge_index_build
                set lock_version = lock_version + 2, updated_at = now()
                where id = ?
                """, build.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("lock_version must increase exactly once");
        assertThatThrownBy(() -> repository.insertVersion(fixture.scope(), new VersionRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                index.id(), build.id(), "1.0.0", "guard-index@1.0.0",
                fixture.routeId(), fixture.routeReference(), 3, 1, 1,
                digest('3'), "READY", now)))
                .isInstanceOf(org.jooq.exception.DataAccessException.class)
                .hasMessageContaining("complete VALIDATING build");
    }

    @Test
    void embeddingEntryBatchRollsBackCompletelyWhenOneEntryViolatesPersistenceConstraints() {
        Fixture fixture = createFixture("atomic-batch");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        IndexRow index = repository.insertIndex(fixture.scope(), new IndexRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                fixture.baseId(), "atomic-index", "Atomic Index", IndexStatus.ACTIVE,
                1, 0, null, now, now));
        BuildRow queued = repository.insertBuild(fixture.scope(), queuedBuild(
                fixture, index.id(), "1.0.0", digest('1'), digest('2'), now));
        repository.insertBuildRevision(
                fixture.scope(), buildRevision(fixture, index.id(), queued.id(), now));
        BuildRow build = activateEmbedding(fixture.scope(), queued.id(), now);
        UUID secondChunkId = UUID.randomUUID();
        sql.update("""
                insert into knowledge_chunk(
                    id, tenant_id, workspace_id, source_revision_id, document_id, ordinal,
                    text, content_digest, start_offset, end_offset, chunker_version, created_at)
                values (?, ?, ?, ?, ?, 1, 'second', ?, 10, 16, 'apvero-boundary@1.0.0', ?)
                """, secondChunkId, fixture.scope().tenantId(), fixture.scope().workspaceId(),
                fixture.revisionId(), fixture.documentId(), digest('3'), now);

        EntryRow first = new EntryRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                build.id(), index.id(), fixture.baseId(), fixture.sourceId(),
                fixture.revisionId(), fixture.documentId(), fixture.chunkId(), 0,
                List.of(1.0F, 0.5F, 0.25F), 3, digest('4'), digest('5'), 0,
                fixture.routeId(), fixture.routeReference(), now);
        EntryRow invalidSecond = new EntryRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                build.id(), index.id(), fixture.baseId(), fixture.sourceId(),
                fixture.revisionId(), fixture.documentId(), secondChunkId, 1,
                List.of(0.25F, 0.5F, 1.0F), 3, "invalid-vector-digest", digest('6'), 0,
                fixture.routeId(), fixture.routeReference(), now);

        assertThatThrownBy(() -> batchWriter.persist(
                fixture.scope(), build.id(), List.of(first, invalidSecond)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_KNOWLEDGE_ENTRY_BATCH_CONFLICT");
        assertThat(repository.listEntries(fixture.scope(), build.id())).isEmpty();
    }

    @Test
    void concurrentEqualEmbeddingBatchesSerializeToOneDurableEntry() throws Exception {
        Fixture fixture = createFixture("concurrent-batch");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        IndexRow index = repository.insertIndex(fixture.scope(), new IndexRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                fixture.baseId(), "concurrent-index", "Concurrent Index", IndexStatus.ACTIVE,
                1, 0, null, now, now));
        BuildRow queued = repository.insertBuild(fixture.scope(), queuedBuild(
                fixture, index.id(), "1.0.0", digest('7'), digest('8'), now));
        repository.insertBuildRevision(
                fixture.scope(), buildRevision(fixture, index.id(), queued.id(), now));
        BuildRow build = activateEmbedding(fixture.scope(), queued.id(), now);
        EntryRow entry = new EntryRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                build.id(), index.id(), fixture.baseId(), fixture.sourceId(),
                fixture.revisionId(), fixture.documentId(), fixture.chunkId(), 0,
                List.of(1.0F, 0.5F, 0.25F), 3, digest('9'), digest('a'), 0,
                fixture.routeId(), fixture.routeReference(), now);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<KnowledgeEmbeddingEntryBatchWriter.BatchWriteOutcome> call = () -> {
                ready.countDown();
                start.await();
                return batchWriter.persist(fixture.scope(), build.id(), List.of(entry));
            };
            Future<KnowledgeEmbeddingEntryBatchWriter.BatchWriteOutcome> first = executor.submit(call);
            Future<KnowledgeEmbeddingEntryBatchWriter.BatchWriteOutcome> second = executor.submit(call);
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(
                            KnowledgeEmbeddingEntryBatchWriter.BatchWriteOutcome.INSERTED,
                            KnowledgeEmbeddingEntryBatchWriter.BatchWriteOutcome.ALREADY_PRESENT);
        }
        assertThat(repository.listEntries(fixture.scope(), build.id()))
                .singleElement()
                .extracting(EntryRow::id)
                .isEqualTo(entry.id());
    }

    @Test
    void repositoriesFailClosedAcrossScopesAndPersistExactLineageAndVectorShape() {
        Fixture fixture = createFixture("scoped");
        WorkspaceScope otherScope = createScope("other");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        RetrievalPolicyRow policy = repository.insertPolicy(fixture.scope(), new RetrievalPolicyRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                "exact-default", "1.0.0", "apvero-exact-cosine-v1",
                "apvero-utf8-byte-v1", 1, 10, 8192, new BigDecimal("0.500000"),
                "KEEP", "NO_EVIDENCE", digest('1'), "test", now));
        IndexRow index = repository.insertIndex(fixture.scope(), new IndexRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                fixture.baseId(), "policy-index", "Policy Index", IndexStatus.ACTIVE,
                1, 0, null, now, now));
        BuildRow queued = repository.insertBuild(fixture.scope(), queuedBuild(
                fixture, index.id(), "1.0.0", digest('2'), digest('3'), now));
        BuildRevisionRow revision = repository.insertBuildRevision(
                fixture.scope(), buildRevision(fixture, index.id(), queued.id(), now));
        BuildRow build = activateEmbedding(fixture.scope(), queued.id(), now);
        EntryRow entry = repository.insertEntry(fixture.scope(), new EntryRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                build.id(), index.id(), fixture.baseId(), fixture.sourceId(),
                fixture.revisionId(), fixture.documentId(), fixture.chunkId(), 0,
                List.of(1.0F, 0.5F, 0.25F), 3, digest('4'), digest('5'), 0,
                fixture.routeId(), fixture.routeReference(), now));

        assertThat(repository.findPolicy(fixture.scope(), policy.id())).contains(policy);
        assertThat(repository.findIndex(fixture.scope(), index.id())).contains(index);
        assertThat(repository.findBuild(fixture.scope(), build.id())).contains(build);
        assertThat(repository.listBuildRevisions(fixture.scope(), build.id()))
                .containsExactly(revision);
        assertThat(repository.listEntries(fixture.scope(), build.id())).containsExactly(entry);

        assertThat(repository.findPolicy(otherScope, policy.id())).isEmpty();
        assertThat(repository.findIndex(otherScope, index.id())).isEmpty();
        assertThat(repository.findBuild(otherScope, build.id())).isEmpty();
        assertThat(repository.lockBuild(otherScope, build.id())).isEmpty();
        assertThat(repository.listBuildRevisions(otherScope, build.id())).isEmpty();
        assertThat(repository.listEntries(otherScope, build.id())).isEmpty();

        assertThatThrownBy(() -> repository.insertIndex(otherScope, index))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_SCOPE_MISMATCH");
        assertThatThrownBy(() -> sql.update("""
                insert into knowledge_index(
                    id, tenant_id, workspace_id, knowledge_base_id, slug, name, status,
                    metadata_version, version_count, created_at, updated_at)
                values (?, ?, ?, ?, 'scope-attack', 'Scope Attack', 'ACTIVE', 1, 0, now(), now())
                """, UUID.randomUUID(), otherScope.tenantId(), otherScope.workspaceId(),
                fixture.baseId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> repository.insertEntry(fixture.scope(), new EntryRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                build.id(), index.id(), fixture.baseId(), fixture.sourceId(),
                fixture.revisionId(), fixture.documentId(), fixture.chunkId(), 1,
                List.of(1.0F, 0.5F), 3, digest('6'), digest('7'), 0,
                fixture.routeId(), fixture.routeReference(), now)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_VECTOR_DIMENSION_MISMATCH");
    }

    @Test
    void publishedArtifactsRejectEveryMutationAndFailedUnpublishedBuildRemainsInspectable() {
        Fixture fixture = createFixture("immutable");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        IndexRow index = repository.insertIndex(fixture.scope(), new IndexRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                fixture.baseId(), "immutable-index", "Immutable Index", IndexStatus.ACTIVE,
                1, 0, null, now, now));
        BuildRow queued = repository.insertBuild(fixture.scope(), queuedBuild(
                fixture, index.id(), "1.0.0", digest('8'), digest('9'), now));
        BuildRevisionRow revision = repository.insertBuildRevision(
                fixture.scope(), buildRevision(fixture, index.id(), queued.id(), now));
        BuildRow build = activateEmbedding(fixture.scope(), queued.id(), now);
        EntryRow entry = repository.insertEntry(fixture.scope(), new EntryRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                build.id(), index.id(), fixture.baseId(), fixture.sourceId(),
                fixture.revisionId(), fixture.documentId(), fixture.chunkId(), 0,
                List.of(0.25F, 0.5F, 1.0F), 3, digest('a'), digest('b'), 0,
                fixture.routeId(), fixture.routeReference(), now));
        UUID versionId = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> {
            sql.update("""
                    update knowledge_index_build
                    set status = 'INDEXING', current_step = 'INDEXING',
                        lock_version = lock_version + 1, updated_at = ?
                    where id = ?
                    """, now, build.id());
            sql.update("""
                    update knowledge_index_build
                    set status = 'VALIDATING', current_step = 'VALIDATING',
                        embedded_entry_count = 1, validated_entry_count = 1,
                        validation_digest = ?, artifact_digest = ?,
                        lock_version = lock_version + 1, updated_at = ?
                    where id = ?
                    """, digest('c'), digest('d'), now, build.id());
            repository.insertVersion(fixture.scope(), new VersionRow(
                    versionId, fixture.scope().tenantId(), fixture.scope().workspaceId(),
                    index.id(), build.id(), "1.0.0", "immutable-index@1.0.0",
                    fixture.routeId(), fixture.routeReference(), 3, 1, 1,
                    digest('d'), "READY", now));
            sql.update("""
                    update knowledge_index_build
                    set status = 'READY', current_step = 'COMPLETE',
                        published_version_id = ?, completed_at = ?,
                        lock_version = lock_version + 1, updated_at = ?
                    where id = ?
                    """, versionId, now, now, build.id());
            sql.update("""
                    update knowledge_index
                    set latest_ready_version_id = ?, version_count = 1,
                        metadata_version = metadata_version + 1, updated_at = ?
                    where id = ?
                    """, versionId, now, index.id());
        });

        assertThat(repository.findVersion(fixture.scope(), versionId))
                .get()
                .extracting(VersionRow::artifactDigest)
                .isEqualTo(digest('d'));
        assertThatThrownBy(() -> sql.update(
                "update knowledge_index_version set artifact_digest = ? where id = ?",
                digest('e'), versionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("knowledge_index_version is immutable");
        assertThatThrownBy(() -> sql.update(
                "update knowledge_index_build set error_code = 'changed' where id = ?", build.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("terminal knowledge_index_build is immutable");
        assertThatThrownBy(() -> sql.update(
                "delete from knowledge_index_build_revision where id = ?", revision.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("knowledge_index_build_revision is immutable");
        assertThatThrownBy(() -> sql.update(
                "update knowledge_index_entry set batch_ordinal = 2 where id = ?", entry.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("knowledge_index_entry is immutable");
        assertThatThrownBy(() -> repository.insertEntry(fixture.scope(), new EntryRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                build.id(), index.id(), fixture.baseId(), fixture.sourceId(),
                fixture.revisionId(), fixture.documentId(), UUID.randomUUID(), 1,
                List.of(1.0F, 1.0F, 1.0F), 3, digest('e'), digest('f'), 1,
                fixture.routeId(), fixture.routeReference(), now)))
                .isInstanceOf(org.jooq.exception.DataAccessException.class)
                .hasMessageContaining("knowledge index entries require an unpublished EMBEDDING build");

        BuildRow failed = repository.insertBuild(fixture.scope(), failedBuild(
                fixture, index.id(), "2.0.0", digest('f'), digest('0'), now.plusSeconds(1)));
        assertThat(repository.findBuild(fixture.scope(), failed.id())).contains(failed);
        assertThatThrownBy(() -> sql.update(
                "delete from knowledge_index_build where id = ?", failed.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("knowledge_index_build is durable");
    }

    private Fixture createFixture(String label) {
        WorkspaceScope scope = createScope(label);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID baseId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        String routeName = "embedding-" + suffix;
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
                    convert_to('hello', 'UTF8'), 'SNAPSHOTTED', null, null, ?)
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
                insert into knowledge_chunk(
                    id, tenant_id, workspace_id, source_revision_id, document_id,
                    ordinal, text, content_digest, start_offset, end_offset,
                    paragraph_number, line_start, line_end, chunker_version, created_at)
                values (?, ?, ?, ?, ?, 0, 'hello', ?, 0, 5, 1, 1, 1,
                    'apvero-boundary@1.0.0', ?)
                """, chunkId, scope.tenantId(), scope.workspaceId(), revisionId,
                documentId, digest('3'), now);
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
                """, routeId, scope.tenantId(), scope.workspaceId(), routeName, modelId, now);
        return new Fixture(
                scope, baseId, sourceId, revisionId, documentId, chunkId,
                routeId, routeName + "@1");
    }

    private WorkspaceScope createScope(String label) {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String suffix = tenantId.toString().replace("-", "").substring(0, 12);
        sql.update("insert into tenant(id, slug, name, created_at) values (?, ?, ?, now())",
                tenantId, "t-" + suffix, "Tenant " + label);
        sql.update("""
                insert into workspace(id, tenant_id, slug, name, created_at)
                values (?, ?, ?, ?, now())
                """, workspaceId, tenantId, "w-" + suffix, "Workspace " + label);
        return new WorkspaceScope(tenantId, workspaceId);
    }

    private BuildRow queuedBuild(
            Fixture fixture,
            UUID indexId,
            String version,
            String requestDigest,
            String sourceSetDigest,
            OffsetDateTime now) {
        return build(
                fixture, indexId, version, requestDigest, sourceSetDigest,
                BuildStatus.QUEUED, BuildStep.EMBEDDING, null, null, false, now);
    }

    private BuildRow failedBuild(
            Fixture fixture,
            UUID indexId,
            String version,
            String requestDigest,
            String sourceSetDigest,
            OffsetDateTime now) {
        return build(
                fixture, indexId, version, requestDigest, sourceSetDigest,
                BuildStatus.FAILED, BuildStep.EMBEDDING,
                "APVERO_EMBEDDING_PROVIDER_UNAVAILABLE", "TRANSIENT", false, now);
    }

    private BuildRow build(
            Fixture fixture,
            UUID indexId,
            String version,
            String requestDigest,
            String sourceSetDigest,
            BuildStatus status,
            BuildStep step,
            String errorCode,
            String errorCategory,
            boolean reconciliationRequired,
            OffsetDateTime now) {
        return new BuildRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                indexId, fixture.baseId(), version, fixture.routeId(), fixture.routeReference(),
                3, 8192, 64, "L2", requestDigest, sourceSetDigest, 1, 1,
                status, step, 0, 3, false, null, null, null, 1, false,
                0, 0, null, null, null, null, errorCode, errorCategory,
                reconciliationRequired, "{}", null,
                status == BuildStatus.FAILED ? now : null, now, now);
    }

    private BuildRevisionRow buildRevision(
            Fixture fixture, UUID indexId, UUID buildId, OffsetDateTime now) {
        return new BuildRevisionRow(
                UUID.randomUUID(), fixture.scope().tenantId(), fixture.scope().workspaceId(),
                buildId, indexId, fixture.baseId(), fixture.sourceId(), fixture.revisionId(),
                digest('1'), "apvero-text@1.0.0", "apvero-boundary@1.0.0", 0, now);
    }

    private BuildRow activateEmbedding(WorkspaceScope scope, UUID buildId, OffsetDateTime now) {
        assertThat(sql.update("""
                update knowledge_index_build
                set status = 'EMBEDDING', started_at = ?,
                    lock_version = lock_version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ?
                """, now, now, scope.tenantId(), scope.workspaceId(), buildId)).isEqualTo(1);
        return repository.findBuild(scope, buildId).orElseThrow();
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private int countTables(String schema) {
        return sql.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = ?
                """, Integer.class, schema);
    }

    private int queryInSchema(String schema, String statement) {
        return sql.queryForObject(statement, Integer.class, schema);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(
            WorkspaceScope scope,
            UUID baseId,
            UUID sourceId,
            UUID revisionId,
            UUID documentId,
            UUID chunkId,
            UUID routeId,
            String routeReference) {}
}
