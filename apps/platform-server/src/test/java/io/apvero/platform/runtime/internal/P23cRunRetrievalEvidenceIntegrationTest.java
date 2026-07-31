package io.apvero.platform.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.knowledge.KnowledgeRetrievalHit;
import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import io.apvero.platform.knowledge.KnowledgeSource;
import io.apvero.platform.runtime.RecordRetrievalEvidenceCommand;
import io.apvero.platform.runtime.RunCatalog;
import io.apvero.platform.runtime.RunEvidenceException;
import io.apvero.platform.runtime.RunRetrievalEvidenceCatalog;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
class P23cRunRetrievalEvidenceIntegrationTest {
    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID APPLICATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000001001");
    private static final UUID RELEASE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000002001");
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p23c_test")
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

    @Autowired RunRetrievalEvidenceCatalog evidence;
    @Autowired RunCatalog runs;
    @Autowired JdbcTemplate sql;

    @Test
    @Transactional
    void persistsOrderedScopedEvidenceAndAppliesRetentionBeforeInsert() {
        UUID runId = insertRun();
        UUID firstIndex = UUID.randomUUID();
        UUID firstPolicy = UUID.randomUUID();
        var first = evidence.record(
                WORKSPACE_ID,
                runId,
                command(0, firstIndex, firstPolicy, true, false, "retained"));
        var second = evidence.record(
                WORKSPACE_ID,
                runId,
                command(1, UUID.randomUUID(), UUID.randomUUID(), true, true, "masked"));

        assertThat(first.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.marker()).isEqualTo("[K1]");
            assertThat(hit.content()).isEqualTo("retained");
        });
        assertThat(second.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.marker()).isEqualTo("[K2]");
            assertThat(hit.content()).isNull();
        });

        var projection = evidence.get(WORKSPACE_ID, runId);
        assertThat(projection.runId()).isEqualTo(runId);
        assertThat(projection.retrievals()).extracting(item -> item.sequence())
                .containsExactly(0, 1);
        assertThat(projection.retrievals().get(0).indexVersionId()).isEqualTo(firstIndex);
        assertThat(projection.retrievals().get(0).retrievalPolicyVersionId()).isEqualTo(firstPolicy);
        assertThat(projection.retrievals().get(0).retentionDecisionVersion()).isEqualTo(7);

        assertThatThrownBy(() -> evidence.get(UUID.randomUUID(), runId))
                .isInstanceOf(RunEvidenceException.class)
                .extracting("code")
                .isEqualTo("APVERO_RUNTIME_RUN_NOT_FOUND");
    }

    @Test
    @Transactional
    void rejectsSequenceGapsAndDatabaseMutationWithoutPartialEvidence() {
        UUID runId = insertRun();

        assertThatThrownBy(() -> evidence.record(
                        WORKSPACE_ID,
                        runId,
                        command(1, UUID.randomUUID(), UUID.randomUUID(), true, false, "gap")))
                .isInstanceOf(RunEvidenceException.class)
                .extracting("code")
                .isEqualTo("APVERO_RUNTIME_RETRIEVAL_SEQUENCE_CONFLICT");
        assertThat(sql.queryForObject(
                        "select count(*) from ai_run_retrieval where run_id = ?",
                        Integer.class,
                        runId))
                .isZero();

        var stored = evidence.record(
                WORKSPACE_ID,
                runId,
                command(0, UUID.randomUUID(), UUID.randomUUID(), true, false, "immutable"));
        assertThatThrownBy(() -> sql.update(
                        "update ai_run_retrieval set query_digest = ? where id = ?",
                        digest('f'),
                        stored.retrievalId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @Transactional
    void rejectsHitIdentityMutation() {
        UUID runId = insertRun();
        var stored = evidence.record(
                WORKSPACE_ID,
                runId,
                command(0, UUID.randomUUID(), UUID.randomUUID(), true, false, "immutable"));

        assertThatThrownBy(() -> sql.update(
                        "update ai_run_retrieval_hit set marker = '[K99]' where retrieval_id = ?",
                        stored.retrievalId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @Transactional
    void rejectsOrdinaryEvidenceDeletionOutsideTheControlledRetentionPurge() {
        UUID runId = insertRun();
        var stored = evidence.record(
                WORKSPACE_ID,
                runId,
                command(0, UUID.randomUUID(), UUID.randomUUID(), true, false, "immutable"));

        assertThatThrownBy(() -> sql.update(
                        "delete from ai_run_retrieval where id = ?",
                        stored.retrievalId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("retention purge");
    }

    @Test
    void rollsBackTheParentAndEarlierHitsWhenALaterHitCannotBeStored() {
        UUID runId = insertRun();
        UUID indexId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        var valid = hit(1, "valid");
        var invalidForPostgres = hit(2, "contains-\u0000-nul");
        var result = new KnowledgeRetrievalResult(
                KnowledgeRetrievalResult.Status.MATCHES,
                indexId,
                policyId,
                digest('b'),
                List.of(valid, invalidForPostgres),
                12);
        var command = new RecordRetrievalEvidenceCommand(
                0,
                "employee-index@1.0.0",
                "exact-policy@1.0.0",
                7,
                true,
                false,
                result);

        assertThatThrownBy(() -> evidence.record(WORKSPACE_ID, runId, command))
                .isInstanceOf(DataAccessException.class);
        assertThat(sql.queryForObject(
                        "select count(*) from ai_run_retrieval where run_id = ?",
                        Integer.class,
                        runId))
                .isZero();
        assertThat(sql.queryForObject(
                        "select count(*) from ai_run_retrieval_hit where run_id = ?",
                        Integer.class,
                        runId))
                .isZero();
    }

    @Test
    @Transactional
    void persistsTypedNoEvidenceAndDeletesEvidenceOnlyThroughControlledRunRetention() {
        UUID runId = insertRun();
        UUID indexId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        var result = new KnowledgeRetrievalResult(
                KnowledgeRetrievalResult.Status.NO_EVIDENCE,
                indexId,
                policyId,
                digest('c'),
                List.of(),
                4);
        var stored = evidence.record(
                WORKSPACE_ID,
                runId,
                new RecordRetrievalEvidenceCommand(
                        0,
                        "employee-index@1.0.0",
                        "exact-policy@1.0.0",
                        8,
                        false,
                        true,
                        result));
        assertThat(stored.status()).isEqualTo(KnowledgeRetrievalResult.Status.NO_EVIDENCE);
        assertThat(stored.hits()).isEmpty();

        assertThat(runs.purgeBefore(
                        WORKSPACE_ID,
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)))
                .isPositive();
        assertThat(sql.queryForObject(
                        "select count(*) from ai_run_retrieval where run_id = ?",
                        Integer.class,
                        runId))
                .isZero();
        assertThat(sql.queryForObject(
                        "select count(*) from ai_run_retrieval_hit where run_id = ?",
                        Integer.class,
                        runId))
                .isZero();
    }

    @Test
    void upgradesTheRealV12SchemaToV13AndPreservesHistoricalRuns() throws Exception {
        String schema = "upgrade_v13_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway toV12 = flyway(schema, "12");
            assertThat(toV12.migrate().migrationsExecuted).isEqualTo(12);
            int historicalRuns = count(schema, "select count(*) from ai_run");

            Flyway toV13 = flyway(schema, "13");
            assertThat(toV13.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(count(schema, "select count(*) from ai_run")).isEqualTo(historicalRuns);
            assertThat(count(schema, """
                    select count(*) from information_schema.tables
                    where table_schema = current_schema()
                      and table_name in ('ai_run_retrieval', 'ai_run_retrieval_hit')
                    """)).isEqualTo(2);
            assertThat(count(schema, """
                    select count(*) from information_schema.columns
                    where table_schema = current_schema()
                      and table_name = 'ai_run'
                      and column_name = 'failure_code'
                    """)).isEqualTo(1);
        } finally {
            sql.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void databaseRejectsACommittedParentWhoseDeclaredHitsAreMissing() throws Exception {
        UUID runId = insertRun();
        UUID retrievalId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                    insert into ai_run_retrieval(
                        id, run_id, tenant_id, workspace_id, sequence,
                        index_version_id, index_version_reference,
                        retrieval_policy_version_id, retrieval_policy_version_reference,
                        query_digest, status, hit_count, latency_ms,
                        retention_decision_version, created_at)
                    values (?, ?, ?, ?, 0, ?, 'employee-index@1.0.0',
                        ?, 'exact-policy@1.0.0', ?, 'MATCHES', 1, 4, 7, now())
                    """)) {
                statement.setObject(1, retrievalId);
                statement.setObject(2, runId);
                statement.setObject(3, TENANT_ID);
                statement.setObject(4, WORKSPACE_ID);
                statement.setObject(5, UUID.randomUUID());
                statement.setObject(6, UUID.randomUUID());
                statement.setString(7, digest('d'));
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            assertThatThrownBy(connection::commit)
                    .hasMessageContaining("hit count, status, or rank order is inconsistent");
            connection.rollback();
        }
        assertThat(sql.queryForObject(
                        "select count(*) from ai_run_retrieval where id = ?",
                        Integer.class,
                        retrievalId))
                .isZero();
    }

    @Test
    @Transactional
    void mapsTheStableNullableRunFailureCodeWithoutRewritingHistory() {
        UUID runId = insertFailedRun("APVERO_RUNTIME_PROVIDER_FAILURE");

        assertThat(runs.list(WORKSPACE_ID))
                .filteredOn(run -> run.id().equals(runId))
                .singleElement()
                .extracting(run -> run.failureCode())
                .isEqualTo("APVERO_RUNTIME_PROVIDER_FAILURE");
        assertThat(sql.queryForObject(
                        "select count(*) from ai_run where failure_code is null",
                        Integer.class))
                .isPositive();
    }

    private UUID insertFailedRun(String failureCode) {
        UUID runId = UUID.randomUUID();
        sql.update("""
                insert into ai_run(
                    id, tenant_id, workspace_id, application_id, release_bundle_id,
                    status, provider_id, input, output, latency_ms, prompt_tokens,
                    completion_tokens, cost_micros, trace_id, failure_code, created_at)
                values (?, ?, ?, ?, ?, 'FAILED', 'p23c-test', '{}'::jsonb, '{}'::jsonb,
                    1, 0, 0, 0, ?, ?, now())
                """,
                runId,
                TENANT_ID,
                WORKSPACE_ID,
                APPLICATION_ID,
                RELEASE_ID,
                "p23c-" + runId,
                failureCode);
        return runId;
    }

    private UUID insertRun() {
        UUID runId = UUID.randomUUID();
        sql.update("""
                insert into ai_run(
                    id, tenant_id, workspace_id, application_id, release_bundle_id,
                    status, provider_id, input, output, latency_ms, prompt_tokens,
                    completion_tokens, cost_micros, trace_id, created_at)
                values (?, ?, ?, ?, ?, 'SUCCEEDED', 'p23c-test', '{}'::jsonb, '{}'::jsonb,
                    1, 0, 0, 0, ?, now())
                """,
                runId,
                TENANT_ID,
                WORKSPACE_ID,
                APPLICATION_ID,
                RELEASE_ID,
                "p23c-" + runId);
        return runId;
    }

    private RecordRetrievalEvidenceCommand command(
            int sequence,
            UUID indexId,
            UUID policyId,
            boolean retainPayloads,
            boolean maskSensitiveFields,
            String content) {
        var hit = hit(1, content);
        var result = new KnowledgeRetrievalResult(
                KnowledgeRetrievalResult.Status.MATCHES,
                indexId,
                policyId,
                digest('b'),
                List.of(hit),
                12);
        return new RecordRetrievalEvidenceCommand(
                sequence,
                "employee-index@1.0.0",
                "exact-policy@1.0.0",
                7,
                retainPayloads,
                maskSensitiveFields,
                result);
    }

    private KnowledgeRetrievalHit hit(int rank, String content) {
        return new KnowledgeRetrievalHit(
                rank,
                new BigDecimal("0.91234567890"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                digest('a'),
                content,
                "Employee policy",
                KnowledgeSource.Type.PDF,
                2,
                "Travel",
                3,
                10,
                14);
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private int count(String schema, String query) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            connection.setSchema(schema);
            try (var result = statement.executeQuery(query)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
