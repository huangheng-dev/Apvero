package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.AddInlineKnowledgeSourceRevisionCommand;
import io.apvero.platform.knowledge.AddUploadedKnowledgeSourceRevisionCommand;
import io.apvero.platform.knowledge.CreateInlineKnowledgeSourceCommand;
import io.apvero.platform.knowledge.CreateKnowledgeBaseCommand;
import io.apvero.platform.knowledge.CreateUploadedKnowledgeSourceCommand;
import io.apvero.platform.knowledge.KnowledgeBase;
import io.apvero.platform.knowledge.KnowledgeBaseCatalog;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeIngestionJob;
import io.apvero.platform.knowledge.KnowledgeSource;
import io.apvero.platform.knowledge.KnowledgeSourceCatalog;
import io.apvero.platform.knowledge.SourceIngestionReceipt;
import io.apvero.platform.knowledge.SourceRevisionReceipt;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.jooq.exception.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.MOCK, properties = {
        "apvero.knowledge.enabled=true",
        "apvero.security.mode=enforced",
        "apvero.security.bootstrap-token=p21c-test-bootstrap"
})
@AutoConfigureMockMvc
class P21cKnowledgeSourceCommandIntegrationTest {
    private static final String ADMIN = "Bearer p21c-test-bootstrap";
    private static final String WORKSPACE_HEADER = "X-Apvero-Workspace-Id";
    private static final KnowledgeCommandContext CONTEXT =
            new KnowledgeCommandContext("p21c-test", "127.0.0.1", "p21c-trace");

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p21c_test")
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
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Autowired KnowledgeBaseCatalog bases;
    @Autowired KnowledgeSourceCatalog sources;
    @Autowired JdbcTemplate sql;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void closesInlineRevisionNoOpContentAndTombstoneWorkflow() {
        WorkspaceScope scope = createScope("inline");
        assertThatThrownBy(() -> bases.create(scope.workspaceId(), null, CONTEXT))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo("APVERO_KNOWLEDGE_REQUEST_INVALID");
        KnowledgeBase base = bases.create(scope.workspaceId(),
                new CreateKnowledgeBaseCommand(slug("inline"), "Engineering", "Internal guidance"), CONTEXT);

        SourceIngestionReceipt created = sources.createInline(scope.workspaceId(), base.id(),
                new CreateInlineKnowledgeSourceCommand(
                        KnowledgeSource.Type.MARKDOWN, "Java guide", "# Java\nUse records."), CONTEXT);

        assertThat(created.source().sourceType()).isEqualTo(KnowledgeSource.Type.MARKDOWN);
        assertThat(created.source().revisionCount()).isEqualTo(1);
        assertThat(created.revision().contentDigest()).matches("^sha256:[a-f0-9]{64}$");
        assertThat(created.job().status()).isEqualTo(KnowledgeIngestionJob.Status.QUEUED);
        assertThat(created.job().currentStep()).isEqualTo(KnowledgeIngestionJob.Step.PARSING);
        assertThat(sources.readRevisionContent(scope.workspaceId(), created.revision().id()).bytes())
                .containsExactly("# Java\nUse records.".getBytes(StandardCharsets.UTF_8));

        SourceRevisionReceipt unchanged = sources.addInlineRevision(scope.workspaceId(), created.source().id(),
                new AddInlineKnowledgeSourceRevisionCommand("# Java\nUse records."), CONTEXT);
        assertThat(unchanged.outcome()).isEqualTo(SourceRevisionReceipt.Outcome.UNCHANGED);
        assertThat(unchanged.revision()).isNull();
        assertThat(unchanged.job()).isNull();

        SourceRevisionReceipt changed = sources.addInlineRevision(scope.workspaceId(), created.source().id(),
                new AddInlineKnowledgeSourceRevisionCommand("# Java 25\nUse records."), CONTEXT);
        assertThat(changed.outcome()).isEqualTo(SourceRevisionReceipt.Outcome.CHANGED);
        assertThat(changed.source().revisionCount()).isEqualTo(2);
        assertThat(sources.listRevisions(scope.workspaceId(), created.source().id()))
                .extracting(revision -> revision.revision())
                .containsExactly(2, 1);

        assertThat(sql.queryForObject(
                "select count(*) from knowledge_ingestion_job where workspace_id = ?", Integer.class,
                scope.workspaceId())).isEqualTo(2);
        assertThat(sql.queryForObject(
                "select count(*) from audit_event where workspace_id = ? and action like 'knowledge.%'",
                Integer.class, scope.workspaceId())).isEqualTo(5);

        sources.tombstone(scope.workspaceId(), created.source().id(), CONTEXT);
        sources.tombstone(scope.workspaceId(), created.source().id(), CONTEXT);
        assertThat(sql.queryForObject(
                "select count(*) from audit_event where workspace_id = ? and action like 'knowledge.%'",
                Integer.class, scope.workspaceId())).isEqualTo(6);
        assertThat(sources.listSources(scope.workspaceId(), base.id()).getFirst().status())
                .isEqualTo(KnowledgeSource.Status.TOMBSTONED);
        assertThatThrownBy(() -> sources.addInlineRevision(scope.workspaceId(), created.source().id(),
                        new AddInlineKnowledgeSourceRevisionCommand("blocked"), CONTEXT))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo("APVERO_KNOWLEDGE_SOURCE_TOMBSTONED");
    }

    @Test
    void detectsUploadMediaFromBytesAndKeepsSourceTypeStable() {
        WorkspaceScope scope = createScope("upload");
        KnowledgeBase base = bases.create(scope.workspaceId(),
                new CreateKnowledgeBaseCommand(slug("upload"), "Uploaded", ""), CONTEXT);
        byte[] firstPdf = pdf("first");

        SourceIngestionReceipt created = sources.createUpload(scope.workspaceId(), base.id(),
                new CreateUploadedKnowledgeSourceCommand(
                        "Policies", "spoofed.txt", "text/plain", firstPdf.length,
                        new ByteArrayInputStream(firstPdf)), CONTEXT);
        assertThat(created.source().sourceType()).isEqualTo(KnowledgeSource.Type.PDF);
        assertThat(created.revision().mediaType()).isEqualTo("application/pdf");

        SourceRevisionReceipt unchanged = sources.addUploadRevision(scope.workspaceId(), created.source().id(),
                new AddUploadedKnowledgeSourceRevisionCommand(
                        "anything.bin", "application/octet-stream", firstPdf.length,
                        new ByteArrayInputStream(firstPdf)), CONTEXT);
        assertThat(unchanged.outcome()).isEqualTo(SourceRevisionReceipt.Outcome.UNCHANGED);

        byte[] text = "not a pdf".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> sources.addUploadRevision(scope.workspaceId(), created.source().id(),
                        new AddUploadedKnowledgeSourceRevisionCommand(
                                "replacement.pdf", "application/pdf", text.length,
                                new ByteArrayInputStream(text)), CONTEXT))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo("APVERO_KNOWLEDGE_SOURCE_TYPE_CONFLICT");
    }

    @Test
    void failsClosedForCrossWorkspaceIdentifiers() {
        WorkspaceScope owner = createScope("owner");
        WorkspaceScope outsider = createScope("outsider");
        KnowledgeBase base = bases.create(owner.workspaceId(),
                new CreateKnowledgeBaseCommand(slug("owner"), "Owner", ""), CONTEXT);
        SourceIngestionReceipt source = sources.createInline(owner.workspaceId(), base.id(),
                new CreateInlineKnowledgeSourceCommand(KnowledgeSource.Type.TEXT, "Private", "secret"), CONTEXT);

        assertNotFound(() -> sources.listSources(outsider.workspaceId(), base.id()),
                "APVERO_KNOWLEDGE_BASE_NOT_FOUND");
        assertNotFound(() -> sources.listRevisions(outsider.workspaceId(), source.source().id()),
                "APVERO_KNOWLEDGE_SOURCE_NOT_FOUND");
        assertNotFound(() -> sources.readRevisionContent(outsider.workspaceId(), source.revision().id()),
                "APVERO_KNOWLEDGE_REVISION_NOT_FOUND");
        assertNotFound(() -> sources.tombstone(outsider.workspaceId(), source.source().id(), CONTEXT),
                "APVERO_KNOWLEDGE_SOURCE_NOT_FOUND");
    }

    @Test
    void rollsBackBusinessMutationWhenAuditAppendFails() {
        WorkspaceScope scope = createScope("audit-rollback");
        String rejectedSlug = slug("audit-rollback");
        sql.execute("drop trigger if exists p21c_reject_base_audit on audit_event");
        sql.execute("drop function if exists p21c_reject_base_audit()");
        sql.execute("""
                create function p21c_reject_base_audit() returns trigger as $$
                begin
                    if new.action = 'knowledge.base.created' then
                        raise exception 'p21c audit failure';
                    end if;
                    return new;
                end;
                $$ language plpgsql
                """);
        sql.execute("""
                create trigger p21c_reject_base_audit
                before insert on audit_event
                for each row execute function p21c_reject_base_audit()
                """);
        try {
            assertThatThrownBy(() -> bases.create(scope.workspaceId(),
                            new CreateKnowledgeBaseCommand(rejectedSlug, "Must roll back", ""), CONTEXT))
                    .isInstanceOf(DataAccessException.class);
            assertThat(sql.queryForObject(
                    "select count(*) from knowledge_base where workspace_id = ? and slug = ?",
                    Integer.class, scope.workspaceId(), rejectedSlug)).isZero();
        } finally {
            sql.execute("drop trigger if exists p21c_reject_base_audit on audit_event");
            sql.execute("drop function if exists p21c_reject_base_audit()");
        }
    }

    @Test
    void enforcesHttpScopesAndStreamsAuthorizedSnapshot() throws Exception {
        WorkspaceScope scope = createScope("http");
        String readerResponse = mvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, scope.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"knowledge-reader\",\"scopes\":[\"read\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reader = json.readTree(readerResponse).path("plaintext").stringValue();

        mvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", "Bearer " + reader)
                        .header(WORKSPACE_HEADER, scope.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"forbidden\",\"name\":\"Forbidden\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APVERO_ACCESS_DENIED"));

        String slug = slug("http");
        String baseResponse = mvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, scope.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"" + slug + "\",\"name\":\"HTTP Base\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(slug))
                .andReturn().getResponse().getContentAsString();
        String baseId = json.readTree(baseResponse).path("id").stringValue();

        String sourceResponse = mvc.perform(post("/api/v1/knowledge-bases/{baseId}/sources", baseId)
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, scope.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"TEXT\",\"name\":\"HTTP Source\",\"content\":\"immutable bytes\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        String revisionId = json.readTree(sourceResponse).path("revision").path("id").stringValue();

        mvc.perform(get("/api/v1/knowledge-source-revisions/{revisionId}/content", revisionId)
                        .header("Authorization", "Bearer " + reader)
                        .header(WORKSPACE_HEADER, scope.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(content().bytes("immutable bytes".getBytes(StandardCharsets.UTF_8)))
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")));
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

    private static void assertNotFound(ThrowingOperation operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo(code);
    }

    private static String slug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static byte[] pdf(String value) {
        return ("%PDF-1.4\n" + value + "\n%%EOF").getBytes(StandardCharsets.ISO_8859_1);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
