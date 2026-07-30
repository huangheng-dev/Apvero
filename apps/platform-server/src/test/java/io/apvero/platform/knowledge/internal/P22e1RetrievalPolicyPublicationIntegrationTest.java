package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.CreateRetrievalPolicyVersionCommand;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.RetrievalPolicyOverlapHandling;
import io.apvero.platform.knowledge.RetrievalPolicyVersion;
import io.apvero.platform.knowledge.RetrievalPolicyVersionCatalog;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.MOCK, properties = {
        "apvero.knowledge.enabled=true",
        "apvero.knowledge.runner.enabled=false",
        "apvero.knowledge.index-build-runner.enabled=false",
        "apvero.security.mode=enforced",
        "apvero.security.bootstrap-token=p22e1-test-bootstrap"
})
@AutoConfigureMockMvc
class P22e1RetrievalPolicyPublicationIntegrationTest {
    private static final String ADMIN = "Bearer p22e1-test-bootstrap";
    private static final String WORKSPACE_HEADER = "X-Apvero-Workspace-Id";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p22e1_test")
            .withUsername("apvero")
            .withPassword("apvero")
            .withStartupTimeout(Duration.ofMinutes(3));

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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate sql;
    @Autowired RetrievalPolicyVersionCatalog policies;

    @Test
    void closesPublicationIdempotencyPermissionIsolationAndAuditWorkflow() throws Exception {
        WorkspaceScope owner = createScope("owner");
        WorkspaceScope outsider = createScope("outsider");
        String request = request("support", "1.0.0", 8, 4096, "0.75", "COLLAPSE_ADJACENT");

        String first = mvc.perform(post("/api/v1/retrieval-policy-versions")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, owner.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("support@1.0.0"))
                .andExpect(jsonPath("$.topK").value(8))
                .andExpect(jsonPath("$.maxContextTokens").value(4096))
                .andExpect(jsonPath("$.minimumScore").value(0.75))
                .andExpect(jsonPath("$.overlapHandling").value("COLLAPSE_ADJACENT"))
                .andExpect(jsonPath("$.retrievalAlgorithmVersion").value("exact-cosine@1.0.0"))
                .andExpect(jsonPath("$.tokenEstimatorVersion").value("apvero-utf8-byte@1.0.0"))
                .andExpect(jsonPath("$.retentionPolicyVersionAtPublish").value(1))
                .andExpect(jsonPath("$.policyDigest").isNotEmpty())
                .andExpect(jsonPath("$.emptyEvidenceBehavior").value("NO_EVIDENCE"))
                .andReturn().getResponse().getContentAsString();
        UUID policyId = UUID.fromString(json.readTree(first).path("id").stringValue());

        mvc.perform(post("/api/v1/retrieval-policy-versions")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, owner.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(policyId.toString()));

        assertThat(sql.queryForObject(
                "select version from retention_policy where workspace_id = ?",
                Long.class,
                owner.workspaceId())).isEqualTo(1L);
        assertThat(sql.queryForObject(
                "select count(*) from retrieval_policy_version where workspace_id = ?",
                Integer.class,
                owner.workspaceId())).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select count(*) from audit_event
                where workspace_id = ? and action = 'knowledge.retrieval-policy.published'
                    and details->>'digest' = (
                        select policy_digest from retrieval_policy_version where id = ?
                    )
                """, Integer.class, owner.workspaceId(), policyId)).isEqualTo(1);

        String reader = createReader(owner);
        mvc.perform(get("/api/v1/retrieval-policy-versions")
                        .header("Authorization", "Bearer " + reader)
                        .header(WORKSPACE_HEADER, owner.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(policyId.toString()));
        mvc.perform(post("/api/v1/retrieval-policy-versions")
                        .header("Authorization", "Bearer " + reader)
                        .header(WORKSPACE_HEADER, owner.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("blocked", "1.0.0", 4, 2048, "0.5", "KEEP")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APVERO_ACCESS_DENIED"));

        mvc.perform(get("/api/v1/retrieval-policy-versions")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, outsider.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mvc.perform(post("/api/v1/retrieval-policy-versions")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, owner.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("support", "1.0.0", 9, 4096, "0.75", "COLLAPSE_ADJACENT")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("APVERO_KNOWLEDGE_RETRIEVAL_POLICY_VERSION_CONFLICT"));

        mvc.perform(post("/api/v1/retrieval-policy-versions")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, owner.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("duplicate", "2.0.0", 8, 4096, "0.75", "COLLAPSE_ADJACENT")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("APVERO_KNOWLEDGE_RETRIEVAL_POLICY_DUPLICATE"));

        mvc.perform(post("/api/v1/retrieval-policy-versions")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, owner.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("invalid", "1.0.0", 1, 127, "0", "KEEP")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("APVERO_KNOWLEDGE_RETRIEVAL_POLICY_REQUEST_INVALID"));
    }

    @Test
    void concurrentFirstPublicationConvergesOnOneRetentionVersionPolicyAndAudit() throws Exception {
        WorkspaceScope scope = createScope("concurrent");
        var command = new CreateRetrievalPolicyVersionCommand(
                "concurrent",
                "1.0.0",
                10,
                8192,
                new BigDecimal("0.6"),
                RetrievalPolicyOverlapHandling.KEEP);
        List<Callable<RetrievalPolicyVersion>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int attempt = index;
            calls.add(() -> policies.publish(
                    scope.workspaceId(),
                    command,
                    new KnowledgeCommandContext(
                            "concurrent-maintainer",
                            "127.0.0.1",
                            "concurrent-" + attempt)));
        }

        List<RetrievalPolicyVersion> results;
        try (var executor = Executors.newFixedThreadPool(8)) {
            results = executor.invokeAll(calls).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();
        }

        Set<UUID> policyIds = results.stream().map(RetrievalPolicyVersion::id)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(policyIds).hasSize(1);
        assertThat(results).allMatch(policy -> policy.retentionPolicyVersionAtPublish() == 1);
        assertThat(sql.queryForObject(
                "select count(*) from retention_policy where workspace_id = ? and version = 1",
                Integer.class,
                scope.workspaceId())).isEqualTo(1);
        assertThat(sql.queryForObject(
                "select count(*) from retrieval_policy_version where workspace_id = ?",
                Integer.class,
                scope.workspaceId())).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select count(*) from audit_event
                where workspace_id = ? and action = 'knowledge.retrieval-policy.published'
                """, Integer.class, scope.workspaceId())).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackPolicyAndDefaultRetentionMaterialization() {
        WorkspaceScope scope = createScope("audit-rollback");
        sql.execute("""
                create function p22e1_reject_policy_audit() returns trigger language plpgsql as $$
                begin
                    if new.action = 'knowledge.retrieval-policy.published' then
                        raise exception 'reject policy audit';
                    end if;
                    return new;
                end
                $$
                """);
        sql.execute("""
                create trigger p22e1_reject_policy_audit
                before insert on audit_event
                for each row execute function p22e1_reject_policy_audit()
                """);
        try {
            assertThatThrownBy(() -> policies.publish(
                            scope.workspaceId(),
                            new CreateRetrievalPolicyVersionCommand(
                                    "rollback",
                                    "1.0.0",
                                    4,
                                    2048,
                                    new BigDecimal("0.5"),
                                    RetrievalPolicyOverlapHandling.KEEP),
                            new KnowledgeCommandContext("maintainer", "127.0.0.1", "rollback-trace")))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class);
            assertThat(sql.queryForObject(
                    "select count(*) from retrieval_policy_version where workspace_id = ?",
                    Integer.class,
                    scope.workspaceId())).isZero();
            assertThat(sql.queryForObject(
                    "select count(*) from retention_policy where workspace_id = ?",
                    Integer.class,
                    scope.workspaceId())).isZero();
        } finally {
            sql.execute("drop trigger if exists p22e1_reject_policy_audit on audit_event");
            sql.execute("drop function if exists p22e1_reject_policy_audit()");
        }
    }

    private String createReader(WorkspaceScope scope) throws Exception {
        String response = mvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, scope.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"retrieval-policy-reader\",\"scopes\":[\"read\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = json.readTree(response);
        return body.path("plaintext").stringValue();
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

    private static String request(
            String slug,
            String version,
            int topK,
            int maxContextTokens,
            String minimumScore,
            String overlapHandling) {
        return """
                {
                  "slug": "%s",
                  "version": "%s",
                  "topK": %d,
                  "maxContextTokens": %d,
                  "minimumScore": %s,
                  "overlapHandling": "%s"
                }
                """.formatted(
                slug, version, topK, maxContextTokens, minimumScore, overlapHandling);
    }
}
