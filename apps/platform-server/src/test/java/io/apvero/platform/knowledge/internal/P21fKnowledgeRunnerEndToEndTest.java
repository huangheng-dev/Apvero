package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.CreateInlineKnowledgeSourceCommand;
import io.apvero.platform.knowledge.CreateKnowledgeBaseCommand;
import io.apvero.platform.knowledge.KnowledgeBase;
import io.apvero.platform.knowledge.KnowledgeBaseCatalog;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeIngestionJob;
import io.apvero.platform.knowledge.KnowledgeIngestionJobCatalog;
import io.apvero.platform.knowledge.KnowledgeSource;
import io.apvero.platform.knowledge.KnowledgeSourceCatalog;
import io.apvero.platform.knowledge.SourceIngestionReceipt;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedBatch;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedChunk;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedDocument;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.SourceAnchors;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "apvero.knowledge.enabled=true",
        "apvero.knowledge.runner.enabled=true",
        "apvero.knowledge.runner.poll-interval=1h",
        "apvero.knowledge.runner.concurrency=1",
        "apvero.knowledge.runner.claim-batch=1"
})
class P21fKnowledgeRunnerEndToEndTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p21f_e2e_test")
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
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @Autowired KnowledgeBaseCatalog bases;
    @Autowired KnowledgeSourceCatalog sources;
    @Autowired KnowledgeIngestionJobCatalog jobs;
    @Autowired KnowledgeIngestionRunner runner;
    @Autowired KnowledgePersistenceRepository repository;
    @Autowired JdbcTemplate sql;
    @Autowired MeterRegistry meters;
    @MockitoBean KnowledgeWorkerClient worker;

    @Test
    void closesSourceToDurableReadyDocumentsAndChunks() {
        WorkspaceScope scope = createScope();
        when(worker.process(any(UUID.class), any(SourceRevisionRow.class)))
                .thenAnswer(invocation -> batch(invocation.getArgument(0), invocation.getArgument(1)));
        KnowledgeBase base = bases.create(scope.workspaceId(),
                new CreateKnowledgeBaseCommand("runner-e2e", "Runner E2E", ""), context());
        SourceIngestionReceipt receipt = sources.createInline(scope.workspaceId(), base.id(),
                new CreateInlineKnowledgeSourceCommand(KnowledgeSource.Type.TEXT, "Greeting", "hello"), context());

        runner.poll();
        KnowledgeIngestionJob completed = awaitTerminal(scope.workspaceId(), receipt.job().id());

        assertThat(completed.status()).isEqualTo(KnowledgeIngestionJob.Status.READY);
        assertThat(completed.currentStep()).isEqualTo(KnowledgeIngestionJob.Step.COMPLETE);
        assertThat(repository.listDocuments(scope, receipt.revision().id())).hasSize(1);
        assertThat(repository.listChunks(scope, receipt.revision().id()))
                .singleElement().extracting(KnowledgePersistenceRecords.ChunkRow::text).isEqualTo("hello");
        assertThat(sql.queryForObject(
                "select count(*) from audit_event where workspace_id = ? and action = 'knowledge.ingestion.ready'",
                Integer.class, scope.workspaceId())).isEqualTo(1);
        assertThat(meters.find("apvero.knowledge.worker.latency")
                .tag("source_type", "text").timer()).isNotNull();
        assertThat(meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("apvero.knowledge.ingestion"))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getKey))
                .doesNotContain("tenant", "workspace", "source", "revision", "job", "url", "filename");
    }

    private KnowledgeIngestionJob awaitTerminal(UUID workspaceId, UUID jobId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        KnowledgeIngestionJob current;
        do {
            current = jobs.get(workspaceId, jobId);
            if (current.status() == KnowledgeIngestionJob.Status.READY
                    || current.status() == KnowledgeIngestionJob.Status.FAILED) {
                return current;
            }
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        } while (System.nanoTime() < deadline);
        return current;
    }

    private WorkspaceScope createScope() {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String suffix = tenantId.toString().replace("-", "").substring(0, 12);
        sql.update("insert into tenant(id, slug, name, created_at) values (?, ?, ?, now())",
                tenantId, "t-" + suffix, "Tenant");
        sql.update("insert into workspace(id, tenant_id, slug, name, created_at) values (?, ?, ?, ?, now())",
                workspaceId, tenantId, "w-" + suffix, "Workspace");
        return new WorkspaceScope(tenantId, workspaceId);
    }

    private static ProcessedBatch batch(UUID requestId, SourceRevisionRow revision) {
        String text = new String(revision.snapshotBytes(), StandardCharsets.UTF_8);
        String digest = digest(text);
        ProcessedChunk chunk = new ProcessedChunk(
                0, text, digest, 0, text.codePointCount(0, text.length()),
                new SourceAnchors(null, null, 1, 1, 1));
        ProcessedDocument document = new ProcessedDocument(0, null, digest, List.of(chunk));
        return new ProcessedBatch(
                requestId, revision.id(), revision.contentDigest(), "apvero-default@1.0.0",
                "apvero-text@1.0.0", "apvero-boundary@1.0.0", List.of(document), List.of());
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static KnowledgeCommandContext context() {
        return new KnowledgeCommandContext("maintainer", "127.0.0.1", UUID.randomUUID().toString());
    }
}
