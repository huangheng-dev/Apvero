package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeIngestionJob;
import io.apvero.platform.knowledge.KnowledgeIngestionJobCatalog;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseStatus;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "apvero.knowledge.enabled=true",
        "apvero.knowledge.runner.enabled=false",
        "apvero.knowledge.runner.backoff-base=10ms",
        "apvero.knowledge.runner.backoff-maximum=20ms"
})
class P21fKnowledgeIngestionRunnerIntegrationTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p21f_test")
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
            registry.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("APVERO_TEST_DB_USER", "apvero"));
            registry.add("spring.datasource.password",
                    () -> System.getenv().getOrDefault("APVERO_TEST_DB_PASSWORD", "apvero"));
        }
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @Autowired KnowledgePersistenceRepository repository;
    @Autowired KnowledgeJobLeaseService leases;
    @Autowired KnowledgeIngestionJobCatalog jobs;
    @Autowired JdbcTemplate sql;

    @Test
    void claimIsScopedExclusiveAndCancellationCannotLieAboutActiveWork() {
        WorkspaceScope owner = createScope("claim-owner");
        WorkspaceScope other = createScope("claim-other");
        IngestionJobRow ownerJob = insertParsingJob(owner);
        insertParsingJob(other);

        IngestionJobRow claimed = leases.claim(owner, "runner-a", 1).getFirst();
        assertThat(claimed.id()).isEqualTo(ownerJob.id());
        assertThat(claimed.status()).isEqualTo(JobStatus.PARSING);
        assertThat(claimed.attemptCount()).isEqualTo(1);
        assertThat(leases.claim(owner, "runner-b", 1)).isEmpty();
        assertThat(leases.claim(other, "runner-b", 1)).hasSize(1);

        assertThatThrownBy(() -> jobs.cancel(owner.workspaceId(), ownerJob.id(), context()))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo("APVERO_KNOWLEDGE_JOB_NOT_CANCELLABLE");
    }

    @Test
    void expiredLeasesRecoverAndExhaustionAllowsExplicitManualRetry() {
        WorkspaceScope scope = createScope("recovery");
        IngestionJobRow job = insertParsingJob(scope);

        IngestionJobRow first = leases.claim(scope, "runner-a", 1).getFirst();
        expire(first.id());
        IngestionJobRow second = leases.claim(scope, "runner-b", 1).getFirst();
        assertThat(second.attemptCount()).isEqualTo(2);
        expire(second.id());
        IngestionJobRow third = leases.claim(scope, "runner-c", 1).getFirst();
        assertThat(third.attemptCount()).isEqualTo(3);
        expire(third.id());

        assertThat(leases.claim(scope, "runner-d", 1)).isEmpty();
        KnowledgeIngestionJob exhausted = jobs.get(scope.workspaceId(), job.id());
        assertThat(exhausted.status()).isEqualTo(KnowledgeIngestionJob.Status.FAILED);
        assertThat(exhausted.retryable()).isTrue();
        assertThat(exhausted.errorCode()).isEqualTo("APVERO_KNOWLEDGE_RETRY_EXHAUSTED");

        KnowledgeIngestionJob retried = jobs.retry(scope.workspaceId(), job.id(), context());
        assertThat(retried.status()).isEqualTo(KnowledgeIngestionJob.Status.QUEUED);
        assertThat(retried.attemptCount()).isZero();
        assertThat(retried.completedAt()).isNull();
    }

    @Test
    void readsAndCommandsFailClosedAcrossWorkspaceScope() {
        WorkspaceScope owner = createScope("api-owner");
        WorkspaceScope attacker = createScope("api-attacker");
        IngestionJobRow job = insertParsingJob(owner);

        assertThat(jobs.list(owner.workspaceId(), null, null)).extracting(KnowledgeIngestionJob::id)
                .contains(job.id());
        assertThat(jobs.list(attacker.workspaceId(), null, null)).isEmpty();
        assertThatThrownBy(() -> jobs.get(attacker.workspaceId(), job.id()))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo("APVERO_KNOWLEDGE_JOB_NOT_FOUND");

        KnowledgeIngestionJob cancelled = jobs.cancel(owner.workspaceId(), job.id(), context());
        assertThat(cancelled.status()).isEqualTo(KnowledgeIngestionJob.Status.CANCELLED);
        assertThat(cancelled.completedAt()).isNotNull();
        assertThatThrownBy(() -> jobs.retry(owner.workspaceId(), job.id(), context()))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo("APVERO_KNOWLEDGE_JOB_NOT_RETRYABLE");
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

    private IngestionJobRow insertParsingJob(WorkspaceScope scope) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BaseRow base = repository.insertBase(scope, new BaseRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), "base-" + suffix,
                "Base", "", BaseStatus.ACTIVE, 1, now, now));
        SourceRow source = repository.insertSource(scope, new SourceRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), base.id(), "Source",
                SourceType.TEXT, SourceStatus.ACTIVE, null, 0, null, 1, null, null, now, now));
        byte[] snapshot = "hello".getBytes(StandardCharsets.UTF_8);
        SourceRevisionRow revision = repository.insertRevision(scope, new SourceRevisionRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), source.id(), 1,
                digest(snapshot), "text/plain", snapshot.length, null, "{}", snapshot,
                SnapshotStatus.SNAPSHOTTED, null, null, now));
        SourceRow updated = repository.updateSourceRevision(
                scope, source.id(), source.version(), 1, revision.id(), now).orElseThrow();
        UUID jobId = UUID.randomUUID();
        return repository.insertJob(scope, new IngestionJobRow(
                jobId, scope.tenantId(), scope.workspaceId(), base.id(), updated.id(), revision.id(),
                JobKind.CREATE_SOURCE, JobStatus.QUEUED, JobStep.PARSING, SyncOutcome.CHANGED,
                0, 3, null, null, null, 1, "test:" + jobId, false, null, null, "{}",
                false, null, null, now, now));
    }

    private void expire(UUID jobId) {
        sql.update("update knowledge_ingestion_job set lease_until = now() - interval '1 second' where id = ?", jobId);
    }

    private static KnowledgeCommandContext context() {
        return new KnowledgeCommandContext("maintainer", "127.0.0.1", UUID.randomUUID().toString());
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
