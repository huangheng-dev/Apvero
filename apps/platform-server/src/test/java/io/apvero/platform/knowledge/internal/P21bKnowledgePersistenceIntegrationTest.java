package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ChunkRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.DocumentRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobKind;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStep;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SnapshotStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
class P21bKnowledgePersistenceIntegrationTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p21b_test")
            .withUsername("apvero")
            .withPassword("apvero");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String externalUrl = System.getenv("APVERO_TEST_DB_URL");
        if (externalUrl == null || externalUrl.isBlank()) {
            POSTGRES.start();
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("APVERO_TEST_DB_USER", "apvero"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("APVERO_TEST_DB_PASSWORD", "apvero"));
        }
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @Autowired KnowledgePersistenceRepository repository;
    @Autowired JdbcTemplate sql;

    @Test
    void cleanMigrationCreatesTheSixScopedTablesIndexesAndTriggers() {
        assertThat(sql.queryForObject(
                "select version from flyway_schema_history where success order by installed_rank desc limit 1",
                String.class)).isEqualTo("8");

        Integer tableCount = sql.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = current_schema()
                  and table_name in (
                    'knowledge_base', 'knowledge_source', 'knowledge_source_revision',
                    'knowledge_document', 'knowledge_chunk', 'knowledge_ingestion_job')
                """, Integer.class);
        assertThat(tableCount).isEqualTo(6);

        Integer scopeForeignKeys = sql.queryForObject("""
                select count(*)
                from information_schema.table_constraints
                where table_schema = current_schema()
                  and table_name in (
                    'knowledge_base', 'knowledge_source', 'knowledge_source_revision',
                    'knowledge_document', 'knowledge_chunk', 'knowledge_ingestion_job')
                  and constraint_type = 'FOREIGN KEY'
                """, Integer.class);
        assertThat(scopeForeignKeys).isGreaterThanOrEqualTo(10);

        Integer immutableTriggers = sql.queryForObject("""
                select count(*)
                from information_schema.triggers
                where trigger_schema = current_schema()
                  and trigger_name in (
                    'knowledge_source_revision_is_insert_only',
                    'knowledge_document_is_insert_only',
                    'knowledge_chunk_is_insert_only')
                """, Integer.class);
        assertThat(immutableTriggers).isEqualTo(6);

        Integer claimIndex = sql.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = current_schema() and indexname = 'idx_knowledge_job_claim'
                """, Integer.class);
        assertThat(claimIndex).isEqualTo(1);
    }

    @Test
    void upgradesARealV7SchemaToV8WithoutRewritingExistingState() throws Exception {
        Assumptions.assumeTrue(POSTGRES.isRunning(), "V7 upgrade verification requires an isolated Testcontainer");
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway toV7 = Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("7"))
                    .load();
            assertThat(toV7.migrate().migrationsExecuted).isEqualTo(7);
            assertThat(toV7.info().current().getVersion().getVersion()).isEqualTo("7");

            Flyway toHead = Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load();
            assertThat(toHead.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(toHead.info().current().getVersion().getVersion()).isEqualTo("8");

            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                connection.setSchema(schema);
                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.tables
                        where table_schema = ? and table_name like 'knowledge_%'
                        """)) {
                    statement.setString(1, schema);
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getInt(1)).isEqualTo(6);
                    }
                }
            }
        } finally {
            sql.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void mapsEveryPersistenceRowAndFailsClosedAcrossTenantAndWorkspaceScope() {
        WorkspaceScope scope = createScope("mapping");
        WorkspaceScope otherScope = createScope("other");
        Lineage lineage = insertLineage(scope, "mapping");

        assertThat(repository.listBases(scope)).containsExactly(lineage.base());
        assertThat(repository.findBase(scope, lineage.base().id())).contains(lineage.base());
        assertThat(repository.findSource(scope, lineage.source().id())).contains(lineage.source());

        SourceRevisionRow revision = repository.findRevision(scope, lineage.revision().id()).orElseThrow();
        assertThat(revision.contentDigest()).isEqualTo(lineage.revision().contentDigest());
        assertThat(revision.snapshotBytes()).containsExactly(lineage.revision().snapshotBytes());
        byte[] callerCopy = revision.snapshotBytes();
        callerCopy[0] = 'X';
        assertThat(repository.findRevision(scope, lineage.revision().id()).orElseThrow().snapshotBytes())
                .containsExactly(lineage.revision().snapshotBytes());

        assertThat(repository.findDocument(scope, lineage.document().id())).contains(lineage.document());
        assertThat(repository.findChunk(scope, lineage.chunk().id())).contains(lineage.chunk());
        assertThat(repository.findJob(scope, lineage.job().id())).contains(lineage.job());

        assertThat(repository.listBases(otherScope)).isEmpty();
        assertThat(repository.findBase(otherScope, lineage.base().id())).isEmpty();
        assertThat(repository.findSource(otherScope, lineage.source().id())).isEmpty();
        assertThat(repository.findRevision(otherScope, lineage.revision().id())).isEmpty();
        assertThat(repository.findDocument(otherScope, lineage.document().id())).isEmpty();
        assertThat(repository.findChunk(otherScope, lineage.chunk().id())).isEmpty();
        assertThat(repository.findJob(otherScope, lineage.job().id())).isEmpty();

        assertThatThrownBy(() -> repository.insertBase(otherScope, lineage.base()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_SCOPE_MISMATCH");

        assertThatThrownBy(() -> sql.update("""
                insert into knowledge_source(
                    id, tenant_id, workspace_id, knowledge_base_id, name, source_type, status,
                    latest_revision_number, version, created_at, updated_at)
                values (?, ?, ?, ?, 'scope attack', 'TEXT', 'ACTIVE', 0, 1, now(), now())
                """, UUID.randomUUID(), otherScope.tenantId(), otherScope.workspaceId(), lineage.base().id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void databaseRejectsMutationOfImmutableLineage() {
        WorkspaceScope scope = createScope("immutable");
        Lineage lineage = insertLineage(scope, "immutable");

        assertThatThrownBy(() -> sql.update(
                "update knowledge_source_revision set media_type = 'text/markdown' where id = ?",
                lineage.revision().id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("knowledge_source_revision is immutable");
        assertThatThrownBy(() -> sql.update(
                "delete from knowledge_document where id = ?", lineage.document().id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("knowledge_document is immutable");
        assertThatThrownBy(() -> sql.update(
                "update knowledge_chunk set text = 'changed' where id = ?", lineage.chunk().id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("knowledge_chunk is immutable");
    }

    @Test
    void databaseRejectsInvalidDigestOffsetsStatusAndDuplicateLineage() {
        WorkspaceScope scope = createScope("constraints");
        Lineage lineage = insertLineage(scope, "constraints");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        SourceRevisionRow badDigest = new SourceRevisionRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), lineage.source().id(), 2,
                "not-a-digest", "text/plain", 1, null, "{}", new byte[] {1},
                SnapshotStatus.SNAPSHOTTED, null, null, now);
        assertThatThrownBy(() -> repository.insertRevision(scope, badDigest))
                .isInstanceOf(DataAccessException.class);

        ChunkRow badOffsets = new ChunkRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), lineage.revision().id(),
                lineage.document().id(), 1, "bad", digest('e'), 5, 5,
                null, null, null, null, null, "apvero-boundary@1.0.0", now);
        assertThatThrownBy(() -> repository.insertChunk(scope, badOffsets))
                .isInstanceOf(DataAccessException.class);

        IngestionJobRow badState = new IngestionJobRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), lineage.base().id(),
                lineage.source().id(), lineage.revision().id(), JobKind.ADD_REVISION,
                JobStatus.READY, JobStep.PARSING, null, 1, 3, null, null, null, 1,
                "invalid-state", false, null, null, "{}", false, now, now, now, now);
        assertThatThrownBy(() -> repository.insertJob(scope, badState))
                .isInstanceOf(DataAccessException.class);

        SourceRevisionRow duplicateDigest = new SourceRevisionRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), lineage.source().id(), 2,
                lineage.revision().contentDigest(), "text/plain", 5, null, "{}",
                "hello".getBytes(StandardCharsets.UTF_8),
                SnapshotStatus.SNAPSHOTTED, null, null, now);
        assertThatThrownBy(() -> repository.insertRevision(scope, duplicateDigest))
                .isInstanceOf(DataAccessException.class);
    }

    private WorkspaceScope createScope(String label) {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String suffix = tenantId.toString().replace("-", "").substring(0, 12);
        sql.update("insert into tenant(id, slug, name, created_at) values (?, ?, ?, now())",
                tenantId, "t-" + suffix, "Tenant " + label);
        sql.update("insert into workspace(id, tenant_id, slug, name, created_at) values (?, ?, ?, ?, now())",
                workspaceId, tenantId, "w-" + suffix, "Workspace " + label);
        return new WorkspaceScope(tenantId, workspaceId);
    }

    private Lineage insertLineage(WorkspaceScope scope, String label) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BaseRow base = repository.insertBase(scope, new BaseRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), "base-" + suffix,
                "Base " + label, "", BaseStatus.ACTIVE, 1, now, now));
        SourceRow source = repository.insertSource(scope, new SourceRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), base.id(), "Source " + label,
                SourceType.TEXT, SourceStatus.ACTIVE, null, 0, null, 1, null, null, now, now));
        byte[] snapshot = "hello".getBytes(StandardCharsets.UTF_8);
        SourceRevisionRow revision = repository.insertRevision(scope, new SourceRevisionRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), source.id(), 1,
                digest('a'), "text/plain", snapshot.length, null, "{}", snapshot,
                SnapshotStatus.SNAPSHOTTED, null, null, now));
        DocumentRow document = repository.insertDocument(scope, new DocumentRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), revision.id(), 0,
                "Document", digest('b'), "apvero-text@1.0.0", "apvero-default@1.0.0", now));
        ChunkRow chunk = repository.insertChunk(scope, new ChunkRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), revision.id(), document.id(),
                0, "hello", digest('c'), 0, 5, null, null, 1, 1, 1,
                "apvero-boundary@1.0.0", now));
        IngestionJobRow job = repository.insertJob(scope, new IngestionJobRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), base.id(), source.id(), revision.id(),
                JobKind.CREATE_SOURCE, JobStatus.QUEUED, JobStep.PARSING, null,
                0, 3, null, null, null, 1, "create-" + suffix, false,
                null, null, "{}", false, null, null, now, now));
        return new Lineage(base, source, revision, document, chunk, job);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Lineage(
            BaseRow base,
            SourceRow source,
            SourceRevisionRow revision,
            DocumentRow document,
            ChunkRow chunk,
            IngestionJobRow job) {}
}
