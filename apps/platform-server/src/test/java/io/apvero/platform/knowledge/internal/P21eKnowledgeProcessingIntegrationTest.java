package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SnapshotStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceType;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedBatch;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedChunk;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedDocument;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.SourceAnchors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
class P21eKnowledgeProcessingIntegrationTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p21e_test")
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
    @Autowired KnowledgeProcessingCompletion completion;
    @Autowired JdbcTemplate sql;

    @Test
    void persistsOnceReplaysAsANoOpAndRejectsDifferentOutput() {
        WorkspaceScope scope = createScope("idempotent");
        SourceRevisionRow revision = insertRevision(scope, "hello");
        ProcessedBatch original = batch(revision, List.of(document(0, "hello")));

        completion.complete(scope, original);
        var documents = repository.listDocuments(scope, revision.id());
        var chunks = repository.listChunks(scope, revision.id());
        assertThat(documents).hasSize(1);
        assertThat(chunks).hasSize(1);

        completion.complete(scope, original);
        assertThat(repository.listDocuments(scope, revision.id())).isEqualTo(documents);
        assertThat(repository.listChunks(scope, revision.id())).isEqualTo(chunks);

        ProcessedBatch changed = batch(revision, List.of(document(0, "different")));
        assertThatThrownBy(() -> completion.complete(scope, changed))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo("APVERO_KNOWLEDGE_NON_DETERMINISTIC_OUTPUT");
        assertThat(repository.listDocuments(scope, revision.id())).isEqualTo(documents);
        assertThat(repository.listChunks(scope, revision.id())).isEqualTo(chunks);
    }

    @Test
    void failsClosedAcrossWorkspaceScope() {
        WorkspaceScope owner = createScope("owner");
        WorkspaceScope attacker = createScope("attacker");
        SourceRevisionRow revision = insertRevision(owner, "secret");

        assertThatThrownBy(() -> completion.complete(
                attacker, batch(revision, List.of(document(0, "secret")))))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo("APVERO_KNOWLEDGE_REVISION_NOT_FOUND");
        assertThat(repository.listDocuments(owner, revision.id())).isEmpty();
    }

    @Test
    void rollsBackAllRowsWhenPersistenceFailsHalfway() {
        WorkspaceScope scope = createScope("rollback");
        SourceRevisionRow revision = insertRevision(scope, "hello");
        ProcessedBatch duplicateOrdinals = batch(
                revision, List.of(document(0, "hello"), document(0, "again")));

        assertThatThrownBy(() -> completion.complete(scope, duplicateOrdinals))
                .isInstanceOf(DataAccessException.class);
        assertThat(repository.listDocuments(scope, revision.id())).isEmpty();
        assertThat(repository.listChunks(scope, revision.id())).isEmpty();
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

    private SourceRevisionRow insertRevision(WorkspaceScope scope, String content) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BaseRow base = repository.insertBase(scope, new BaseRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), "base-" + suffix,
                "Base", "", BaseStatus.ACTIVE, 1, now, now));
        SourceRow source = repository.insertSource(scope, new SourceRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), base.id(), "Source",
                SourceType.TEXT, SourceStatus.ACTIVE, null, 0, null, 1, null, null, now, now));
        byte[] snapshot = content.getBytes(StandardCharsets.UTF_8);
        return repository.insertRevision(scope, new SourceRevisionRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), source.id(), 1,
                digest(snapshot), "text/plain", snapshot.length, null, "{}", snapshot,
                SnapshotStatus.SNAPSHOTTED, null, null, now));
    }

    private static ProcessedBatch batch(SourceRevisionRow revision, List<ProcessedDocument> documents) {
        return new ProcessedBatch(
                UUID.randomUUID(), revision.id(), revision.contentDigest(), "apvero-default@1.0.0",
                "apvero-text@1.0.0", "apvero-boundary@1.0.0", documents, List.of());
    }

    private static ProcessedDocument document(int ordinal, String text) {
        String digest = digest(text.getBytes(StandardCharsets.UTF_8));
        ProcessedChunk chunk = new ProcessedChunk(
                0, text, digest, 0, text.codePointCount(0, text.length()),
                new SourceAnchors(null, null, 1, 1, 1));
        return new ProcessedDocument(ordinal, null, digest, List.of(chunk));
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
