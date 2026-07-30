package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@EnabledIfEnvironmentVariable(named = "APVERO_P22F_BENCHMARK", matches = "true")
class P22fExactRetrievalBenchmark {
    private static final int WARMUP_RUNS = 5;
    private static final int MEASURED_RUNS = 30;
    private static final int CONCURRENT_CLIENTS = 8;
    private static final int REQUESTS_PER_CLIENT = 10;
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("small-256", 256, 1_000),
            new Scenario("medium-256", 256, 5_000),
            new Scenario("limit-256", 256, 10_000),
            new Scenario("medium-384", 384, 5_000),
            new Scenario("medium-768", 768, 5_000),
            new Scenario("medium-1536", 1_536, 5_000));
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p22f_benchmark")
            .withUsername("apvero")
            .withPassword("apvero")
            .withStartupTimeout(Duration.ofMinutes(3));

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create extension if not exists pg_buffercache");
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot enable benchmark-only pg_buffercache", failure);
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    void measuresProductionExactRetrievalSql() throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        for (Scenario scenario : SCENARIOS) {
            fixtures.add(createFixture(scenario, true));
        }
        analyze();

        List<Measurement> measurements = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            evictSharedBuffers();
            long restartCold = query(fixture, QueryProfile.ENGLISH, 0.0, 10);
            for (int run = 0; run < WARMUP_RUNS; run++) {
                query(fixture, QueryProfile.values()[run % QueryProfile.values().length], 0.0, 10);
            }
            List<Long> warm = new ArrayList<>();
            for (int run = 0; run < MEASURED_RUNS; run++) {
                QueryProfile profile = QueryProfile.values()[run % QueryProfile.values().length];
                warm.add(query(fixture, profile, run % 3 == 0 ? 0.99 : 0.0, run % 5 == 0 ? 100 : 10));
            }
            measurements.add(new Measurement(
                    fixture.scenario(),
                    fixture.buildNanos(),
                    restartCold,
                    percentile(warm, 0.50),
                    percentile(warm, 0.95),
                    percentile(warm, 0.99),
                    explain(fixture)));
        }

        Fixture acceptance = fixtures.stream()
                .filter(fixture -> fixture.scenario().name().equals("limit-256"))
                .findFirst()
                .orElseThrow();
        ConcurrentMeasurement concurrent = runConcurrentReadsAndBuildWrites(acceptance);
        long storageBytes = relationStorageBytes();
        Path report = writeReport(measurements, concurrent, storageBytes);

        assertThat(measurements).hasSize(SCENARIOS.size());
        assertThat(concurrent.completedReads())
                .isEqualTo(CONCURRENT_CLIENTS * REQUESTS_PER_CLIENT);
        assertThat(concurrent.writtenEntries()).isEqualTo(1_000);
        assertThat(Files.size(report)).isGreaterThan(1_000);
    }

    private Fixture createFixture(Scenario scenario, boolean publish) throws SQLException {
        long startedAt = System.nanoTime();
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID indexId = UUID.randomUUID();
        UUID buildId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String suffix = buildId.toString().replace("-", "").substring(0, 12);

        try (Connection connection = connection()) {
            execute(connection,
                    "insert into tenant(id, slug, name, created_at) values (?, ?, ?, now())",
                    tenantId, "t-" + suffix, "Benchmark tenant");
            execute(connection,
                    "insert into workspace(id, tenant_id, slug, name, created_at) "
                            + "values (?, ?, ?, ?, now())",
                    workspaceId, tenantId, "w-" + suffix, "Benchmark workspace");
            execute(connection, """
                    insert into model_provider(
                        id, tenant_id, workspace_id, name, provider_type, base_url,
                        enabled, version, created_at, updated_at)
                    values (?, ?, ?, ?, 'DETERMINISTIC_LOCAL', 'local://benchmark',
                        true, 1, now(), now())
                    """, providerId, tenantId, workspaceId, "Benchmark provider");
            execute(connection, """
                    insert into model_definition(
                        id, tenant_id, workspace_id, provider_id, model_key, name,
                        capabilities, input_cost_micros_per_million,
                        output_cost_micros_per_million, enabled, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, '["EMBEDDING"]'::jsonb, 0, 0, true, now(), now())
                    """, modelId, tenantId, workspaceId, providerId,
                    "benchmark-" + suffix, "Benchmark model");
            execute(connection, """
                    insert into model_route(
                        id, tenant_id, workspace_id, name, version, model_id, status,
                        timeout_ms, route_capability, embedding_dimension,
                        embedding_maximum_input_tokens, embedding_maximum_batch_size,
                        embedding_normalization, created_at)
                    values (?, ?, ?, ?, 1, ?, 'PUBLISHED', 30000, 'EMBEDDING',
                        ?, 8192, 64, 'L2', now())
                    """, routeId, tenantId, workspaceId, "route-" + suffix,
                    modelId, scenario.dimension());
            execute(connection, """
                    insert into knowledge_base(
                        id, tenant_id, workspace_id, slug, name, description, status,
                        version, created_at, updated_at)
                    values (?, ?, ?, ?, ?, '', 'ACTIVE', 1, now(), now())
                    """, baseId, tenantId, workspaceId, "base-" + suffix, "Benchmark base");
            execute(connection, """
                    insert into knowledge_source(
                        id, tenant_id, workspace_id, knowledge_base_id, name, source_type,
                        status, latest_revision_number, version, created_at, updated_at)
                    values (?, ?, ?, ?, ?, 'TEXT', 'ACTIVE', 0, 1, now(), now())
                    """, sourceId, tenantId, workspaceId, baseId, "Benchmark source");
            execute(connection, """
                    insert into knowledge_source_revision(
                        id, tenant_id, workspace_id, source_id, revision, content_digest,
                        media_type, byte_size, capture_metadata, snapshot_bytes, snapshot_status,
                        parser_version, chunker_version, created_at)
                    values (?, ?, ?, ?, 1, ?, 'text/plain', 9, '{}'::jsonb,
                        convert_to('benchmark', 'UTF8'), 'SNAPSHOTTED',
                        'apvero-text@1.0.0', 'apvero-boundary@1.0.0', now())
                    """, revisionId, tenantId, workspaceId, sourceId, digest("revision"));
            execute(connection, """
                    insert into knowledge_document(
                        id, tenant_id, workspace_id, source_revision_id, ordinal, title,
                        normalized_text_digest, parser_version, processing_profile, created_at)
                    values (?, ?, ?, ?, 0, 'Benchmark document', ?,
                        'apvero-text@1.0.0', 'apvero-default@1.0.0', now())
                    """, documentId, tenantId, workspaceId, revisionId, digest("document"));
            execute(connection, """
                    insert into knowledge_ingestion_job(
                        id, tenant_id, workspace_id, knowledge_base_id, source_id,
                        source_revision_id, job_kind, status, current_step, sync_outcome,
                        attempt_count, maximum_attempts, lock_version, idempotency_key,
                        retryable, failure_metadata, cancellation_requested,
                        started_at, completed_at, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, 'CREATE_SOURCE', 'READY', 'COMPLETE', 'CHANGED',
                        1, 3, 1, ?, false, '{}'::jsonb, false, now(), now(), now(), now())
                    """, UUID.randomUUID(), tenantId, workspaceId, baseId, sourceId,
                    revisionId, "benchmark-" + suffix);
            insertChunks(connection, scenario, tenantId, workspaceId, revisionId, documentId, buildId);
            execute(connection, """
                    insert into knowledge_index(
                        id, tenant_id, workspace_id, knowledge_base_id, slug, name, status,
                        metadata_version, version_count, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, 'ACTIVE', 1, 0, now(), now())
                    """, indexId, tenantId, workspaceId, baseId,
                    "index-" + suffix, "Benchmark index");
            execute(connection, """
                    insert into knowledge_index_build(
                        id, tenant_id, workspace_id, knowledge_index_id, knowledge_base_id,
                        requested_version, embedding_route_id, embedding_route_reference,
                        vector_dimension, maximum_input_tokens, maximum_batch_size,
                        normalization, request_digest, source_set_digest, requested_source_count,
                        requested_chunk_count, status, current_step, attempt_count, maximum_attempts,
                        retryable, lock_version, cancellation_requested, embedded_entry_count,
                        validated_entry_count, reconciliation_required, failure_metadata,
                        created_at, updated_at)
                    values (?, ?, ?, ?, ?, '1.0.0', ?, ?, ?, 8192, 64, 'L2', ?, ?, 1, ?,
                        'QUEUED', 'EMBEDDING', 0, 3, false, 1, false, 0, 0, false,
                        '{}'::jsonb, now(), now())
                    """, buildId, tenantId, workspaceId, indexId, baseId, routeId,
                    "route-" + suffix + "@1", scenario.dimension(), digest("request"),
                    digest("source-set"), scenario.corpusSize());
            execute(connection, """
                    insert into knowledge_index_build_revision(
                        id, tenant_id, workspace_id, knowledge_index_build_id,
                        knowledge_index_id, knowledge_base_id, source_id, source_revision_id,
                        source_content_digest, parser_version, chunker_version,
                        source_set_ordinal, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'apvero-text@1.0.0', 'apvero-boundary@1.0.0', 0, now())
                    """, UUID.randomUUID(), tenantId, workspaceId, buildId, indexId,
                    baseId, sourceId, revisionId, digest("revision"));
            execute(connection, """
                    update knowledge_index_build
                    set status = 'EMBEDDING', attempt_count = 1, started_at = now(),
                        lock_version = lock_version + 1, updated_at = now()
                    where id = ?
                    """, buildId);
            if (publish) {
                insertEntries(connection, scenario, tenantId, workspaceId, buildId, indexId,
                        baseId, sourceId, revisionId, documentId, routeId, suffix);
                publish(connection, scenario, tenantId, workspaceId, buildId, indexId,
                        baseId, routeId, versionId, suffix);
            }
        }
        return new Fixture(
                scenario,
                tenantId,
                workspaceId,
                baseId,
                sourceId,
                revisionId,
                documentId,
                indexId,
                buildId,
                routeId,
                versionId,
                suffix,
                System.nanoTime() - startedAt);
    }

    private void insertChunks(
            Connection connection,
            Scenario scenario,
            UUID tenantId,
            UUID workspaceId,
            UUID revisionId,
            UUID documentId,
            UUID buildId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into knowledge_chunk(
                    id, tenant_id, workspace_id, source_revision_id, document_id,
                    ordinal, text, content_digest, start_offset, end_offset,
                    paragraph_number, line_start, line_end, chunker_version, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'apvero-boundary@1.0.0', now())
                """)) {
            for (int ordinal = 0; ordinal < scenario.corpusSize(); ordinal++) {
                String text = switch (ordinal % 3) {
                    case 0 -> "expense policy evidence " + ordinal;
                    case 1 -> "公司报销制度证据 " + ordinal;
                    default -> "Apvero 混合检索 evidence " + ordinal;
                };
                bind(statement, chunkId(buildId, ordinal), tenantId, workspaceId,
                        revisionId, documentId, ordinal, text, digest("chunk-" + ordinal),
                        ordinal * 64, ordinal * 64 + text.length(), ordinal + 1,
                        ordinal + 1, ordinal + 1);
                statement.addBatch();
                if ((ordinal + 1) % 500 == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private void insertEntries(
            Connection connection,
            Scenario scenario,
            UUID tenantId,
            UUID workspaceId,
            UUID buildId,
            UUID indexId,
            UUID baseId,
            UUID sourceId,
            UUID revisionId,
            UUID documentId,
            UUID routeId,
            String suffix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into knowledge_index_entry(
                    id, tenant_id, workspace_id, knowledge_index_build_id,
                    knowledge_index_id, knowledge_base_id, source_id, source_revision_id,
                    document_id, chunk_id, entry_ordinal, embedding, vector_dimension,
                    vector_digest, normalized_input_digest, batch_ordinal,
                    embedding_route_id, embedding_route_reference, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?, ?, ?, ?, ?, now())
                """)) {
            for (int ordinal = 0; ordinal < scenario.corpusSize(); ordinal++) {
                bind(statement, UUID.randomUUID(), tenantId, workspaceId, buildId, indexId,
                        baseId, sourceId, revisionId, documentId, chunkId(buildId, ordinal),
                        ordinal, vector(scenario.dimension(), ordinal), scenario.dimension(),
                        digest("vector-" + ordinal), digest("input-" + ordinal), ordinal / 64,
                        routeId, "route-" + suffix + "@1");
                statement.addBatch();
                if ((ordinal + 1) % 250 == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private void publish(
            Connection connection,
            Scenario scenario,
            UUID tenantId,
            UUID workspaceId,
            UUID buildId,
            UUID indexId,
            UUID baseId,
            UUID routeId,
            UUID versionId,
            String suffix) throws SQLException {
        execute(connection, """
                update knowledge_index_build
                set status = 'INDEXING', current_step = 'INDEXING',
                    embedded_entry_count = ?, last_durable_chunk_ordinal = ?,
                    lock_version = lock_version + 1, updated_at = now()
                where id = ?
                """, scenario.corpusSize(), scenario.corpusSize() - 1, buildId);
        execute(connection, """
                update knowledge_index_build
                set status = 'VALIDATING', current_step = 'VALIDATING',
                    validated_entry_count = ?, validation_digest = ?, artifact_digest = ?,
                    lease_owner = 'p22f-benchmark', lease_until = now() + interval '5 minutes',
                    lock_version = lock_version + 1, updated_at = now()
                where id = ?
                """, scenario.corpusSize(), digest("validation"), digest("artifact"), buildId);
        execute(connection, """
                insert into knowledge_index_version(
                    id, tenant_id, workspace_id, knowledge_index_id,
                    knowledge_index_build_id, version, reference, embedding_route_id,
                    embedding_route_reference, vector_dimension, source_count, chunk_count,
                    artifact_digest, status, published_at)
                values (?, ?, ?, ?, ?, '1.0.0', ?, ?, ?, ?, 1, ?, ?, 'READY', now())
                """, versionId, tenantId, workspaceId, indexId, buildId,
                "index-" + suffix + "@1.0.0", routeId, "route-" + suffix + "@1",
                scenario.dimension(), scenario.corpusSize(), digest("artifact"));
        execute(connection, """
                update knowledge_index_build
                set status = 'READY', current_step = 'COMPLETE', published_version_id = ?,
                    completed_at = now(), lease_owner = null, lease_until = null,
                    lock_version = lock_version + 1, updated_at = now()
                where id = ?
                """, versionId, buildId);
        execute(connection, """
                update knowledge_index
                set latest_ready_version_id = ?, version_count = 1,
                    metadata_version = metadata_version + 1, updated_at = now()
                where id = ?
                """, versionId, indexId);
    }

    private long query(
            Fixture fixture, QueryProfile profile, double minimumScore, int topK)
            throws SQLException {
        long started = System.nanoTime();
        try (Connection connection = connection();
                PreparedStatement statement =
                        connection.prepareStatement(JooqKnowledgeIndexPersistenceRepository.EXACT_RETRIEVAL_SQL)) {
            bind(statement, queryVector(fixture.scenario().dimension(), profile),
                    fixture.tenantId(), fixture.workspaceId(), fixture.versionId(),
                    minimumScore, topK);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    rows.getObject(1);
                }
            }
        }
        return System.nanoTime() - started;
    }

    private ConcurrentMeasurement runConcurrentReadsAndBuildWrites(Fixture fixture)
            throws Exception {
        Fixture writeFixture = createFixture(new Scenario("write-pressure", 256, 1_000), false);
        AtomicLong writeNanos = new AtomicLong();
        List<Callable<List<Long>>> tasks = new ArrayList<>();
        for (int client = 0; client < CONCURRENT_CLIENTS; client++) {
            final int clientIndex = client;
            tasks.add(() -> {
                List<Long> latencies = new ArrayList<>();
                for (int request = 0; request < REQUESTS_PER_CLIENT; request++) {
                    latencies.add(query(
                            fixture,
                            QueryProfile.values()[(clientIndex + request)
                                    % QueryProfile.values().length],
                            0.0,
                            10));
                }
                return latencies;
            });
        }
        tasks.add(() -> {
            long startedAt = System.nanoTime();
            try (Connection connection = connection()) {
                insertEntries(
                        connection,
                        writeFixture.scenario(),
                        writeFixture.tenantId(),
                        writeFixture.workspaceId(),
                        writeFixture.buildId(),
                        writeFixture.indexId(),
                        writeFixture.baseId(),
                        writeFixture.sourceId(),
                        writeFixture.revisionId(),
                        writeFixture.documentId(),
                        writeFixture.routeId(),
                        writeFixture.suffix());
            }
            writeNanos.set(System.nanoTime() - startedAt);
            return List.of();
        });
        List<Long> readLatencies = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(CONCURRENT_CLIENTS + 1)) {
            for (var result : executor.invokeAll(tasks)) {
                readLatencies.addAll(result.get());
            }
        }
        return new ConcurrentMeasurement(
                readLatencies.size(),
                1_000,
                writeNanos.get(),
                percentile(readLatencies, 0.50),
                percentile(readLatencies, 0.95),
                percentile(readLatencies, 0.99));
    }

    private String explain(Fixture fixture) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "explain (analyze, buffers, format text) "
                                + JooqKnowledgeIndexPersistenceRepository.EXACT_RETRIEVAL_SQL)) {
            bind(statement, queryVector(fixture.scenario().dimension(), QueryProfile.MIXED),
                    fixture.tenantId(), fixture.workspaceId(), fixture.versionId(), 0.0, 10);
            StringBuilder plan = new StringBuilder();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                }
            }
            return plan.toString();
        }
    }

    private void analyze() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("analyze knowledge_index_entry");
            statement.execute("analyze knowledge_chunk");
            statement.execute("analyze knowledge_index_version");
        }
    }

    private void evictSharedBuffers() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("checkpoint");
            statement.execute("select pg_buffercache_evict_all()");
        }
    }

    private long relationStorageBytes() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select pg_total_relation_size('knowledge_index_entry')
                             + pg_total_relation_size('knowledge_chunk')
                        """)) {
            result.next();
            return result.getLong(1);
        }
    }

    private Path writeReport(
            List<Measurement> measurements,
            ConcurrentMeasurement concurrent,
            long storageBytes) throws IOException, SQLException {
        Path report = Path.of("build", "reports", "p22f", "exact-retrieval-benchmark.md");
        Files.createDirectories(report.getParent());
        StringBuilder output = new StringBuilder("""
                # P2.2f Exact Retrieval Benchmark

                This generated report is evidence, not a portable SLA. `shared-buffer-cold` is the
                first measured query after PostgreSQL 18 `pg_buffercache_evict_all()`; it does not
                claim an operating-system page-cache flush. Latencies include opening a
                JDBC connection and consuming results. Fixture-publication seconds include direct
                test-data lineage, chunks, vector entries, validation transitions and atomic
                publication; they are not governed Build-runner throughput.

                """);
        output.append("- Host: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append('\n');
        output.append("- JVM: ").append(System.getProperty("java.version")).append('\n');
        output.append("- CPUs visible to JVM: ")
                .append(Runtime.getRuntime().availableProcessors()).append('\n');
        output.append("- PostgreSQL: ").append(databaseVersion()).append('\n');
        output.append("- pgvector: ").append(extensionVersion()).append('\n');
        output.append("- Measured entry + chunk storage bytes: ").append(storageBytes).append("\n\n");
        output.append("| Scenario | Dimension | Entries | Fixture publication s | Fixture entries/s | Shared-buffer-cold ms | p50 ms | p95 ms | p99 ms |\n");
        output.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Measurement measurement : measurements) {
            output.append(String.format(
                    Locale.ROOT,
                    "| %s | %d | %d | %.3f | %.1f | %.3f | %.3f | %.3f | %.3f |%n",
                    measurement.scenario().name(),
                    measurement.scenario().dimension(),
                    measurement.scenario().corpusSize(),
                    seconds(measurement.buildNanos()),
                    measurement.scenario().corpusSize() / seconds(measurement.buildNanos()),
                    millis(measurement.restartColdNanos()),
                    millis(measurement.p50Nanos()),
                    millis(measurement.p95Nanos()),
                    millis(measurement.p99Nanos())));
        }
        output.append("\n## Concurrent reads with simultaneous unpublished Build Entry writes\n\n");
        output.append(String.format(
                Locale.ROOT,
                "- Clients: %d; completed reads: %d; write-pressure entries: %d%n"
                        + "- Direct fixture Entry write: %.3f s (%.1f entries/s)%n"
                        + "- p50: %.3f ms; p95: %.3f ms; p99: %.3f ms%n%n",
                CONCURRENT_CLIENTS,
                concurrent.completedReads(),
                concurrent.writtenEntries(),
                seconds(concurrent.writeNanos()),
                concurrent.writtenEntries() / seconds(concurrent.writeNanos()),
                millis(concurrent.p50Nanos()),
                millis(concurrent.p95Nanos()),
                millis(concurrent.p99Nanos())));
        output.append("## Query plans\n\n");
        for (Measurement measurement : measurements) {
            output.append("### ").append(measurement.scenario().name()).append("\n\n```text\n")
                    .append(measurement.plan()).append("```\n\n");
        }
        Files.writeString(report, output, StandardCharsets.UTF_8);
        return report;
    }

    private String databaseVersion() throws SQLException {
        return scalar("select version()");
    }

    private String extensionVersion() throws SQLException {
        return scalar("select extversion from pg_extension where extname = 'vector'");
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object... arguments)
            throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    private static String vector(int dimension, int seed) {
        double first = 0.25 + Math.abs(Math.sin(seed * 0.013));
        double second = 0.25 + Math.abs(Math.cos(seed * 0.017));
        double norm = Math.sqrt(first * first + second * second);
        StringBuilder vector = new StringBuilder(dimension * 4).append('[')
                .append((float) (first / norm)).append(',')
                .append((float) (second / norm));
        for (int component = 2; component < dimension; component++) {
            vector.append(",0");
        }
        return vector.append(']').toString();
    }

    private static String queryVector(int dimension, QueryProfile profile) {
        return vector(dimension, profile.seed());
    }

    private static UUID chunkId(UUID buildId, int ordinal) {
        return UUID.nameUUIDFromBytes(
                (buildId + ":" + ordinal).getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String input) {
        String value = Integer.toHexString(input.hashCode() * 31);
        return "sha256:" + value.repeat(64 / value.length() + 1).substring(0, 64);
    }

    private static long percentile(List<Long> values, double percentile) {
        assertThat(values).isNotEmpty();
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private enum QueryProfile {
        ENGLISH(11),
        SIMPLIFIED_CHINESE(29),
        MIXED(47);

        private final int seed;

        QueryProfile(int seed) {
            this.seed = seed;
        }

        int seed() {
            return seed;
        }
    }

    private record Scenario(String name, int dimension, int corpusSize) {}

    private record Fixture(
            Scenario scenario,
            UUID tenantId,
            UUID workspaceId,
            UUID baseId,
            UUID sourceId,
            UUID revisionId,
            UUID documentId,
            UUID indexId,
            UUID buildId,
            UUID routeId,
            UUID versionId,
            String suffix,
            long buildNanos) {}

    private record Measurement(
            Scenario scenario,
            long buildNanos,
            long restartColdNanos,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            String plan) {}

    private record ConcurrentMeasurement(
            int completedReads,
            int writtenEntries,
            long writeNanos,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos) {}
}
