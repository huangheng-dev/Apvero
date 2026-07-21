package io.apvero.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import io.apvero.platform.governance.GovernanceMaintenance;
import io.apvero.platform.runtime.RunCatalog;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.dao.DataAccessException;

@SpringBootTest(webEnvironment = WebEnvironment.MOCK, properties = {
        "apvero.security.mode=enforced",
        "apvero.security.bootstrap-token=p1-test-bootstrap"
})
@AutoConfigureMockMvc
class P1GovernanceIntegrationTest {
    private static final String WORKSPACE = "00000000-0000-0000-0000-000000000101";
    private static final String APP_ONE = "00000000-0000-0000-0000-000000001001";
    private static final String APP_TWO = "00000000-0000-0000-0000-000000001002";
    private static final String APP_THREE = "00000000-0000-0000-0000-000000001003";
    private static final String AUTHORIZATION = "Authorization";
    private static final String ADMIN = "Bearer p1-test-bootstrap";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_test")
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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate sql;
    @Autowired ObjectMapper json;
    @Autowired GovernanceMaintenance governance;
    @Autowired RunCatalog runs;

    @Test
    void closesAuthenticationGovernanceAuditAndRuntimeWorkflow() throws Exception {
        mvc.perform(get("/api/v1/runs").header("X-Apvero-Workspace-Id", WORKSPACE))
                .andExpect(status().isUnauthorized());

        String issued = mvc.perform(post("/api/v1/api-keys")
                        .header(AUTHORIZATION, ADMIN)
                        .header("X-Apvero-Workspace-Id", WORKSPACE)
                        .contentType("application/json")
                        .content("{\"name\":\"p1-test-reader\",\"scopes\":[\"read\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String plaintext = json.readTree(issued).path("plaintext").stringValue();
        assertThat(plaintext).startsWith("apv_");

        UUID otherWorkspace = UUID.fromString("00000000-0000-0000-0000-000000000202");
        sql.update("insert into workspace(id, tenant_id, slug, name, created_at) values (?, ?::uuid, 'other', 'Other', now())",
                otherWorkspace, "00000000-0000-0000-0000-000000000001");
        mvc.perform(get("/api/v1/applications")
                        .header(AUTHORIZATION, "Bearer " + plaintext)
                        .header("X-Apvero-Workspace-Id", otherWorkspace))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/retention-policy")
                        .header(AUTHORIZATION, ADMIN)
                        .header("X-Apvero-Workspace-Id", WORKSPACE)
                        .contentType("application/json")
                        .content("{\"runRetentionDays\":30,\"auditRetentionDays\":365,\"retainPayloads\":true,\"maskSensitiveFields\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(post("/api/v1/applications/{id}/preview-runs", APP_ONE)
                        .header(AUTHORIZATION, ADMIN)
                        .header("X-Apvero-Workspace-Id", WORKSPACE)
                        .contentType("application/json")
                        .content("{\"input\":{\"message\":\"P1 test\",\"apiKey\":\"must-not-persist\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.actorId").value("bootstrap-admin"))
                .andExpect(jsonPath("$.input.apiKey").value("***"))
                .andExpect(jsonPath("$.governanceReservationId").isNotEmpty());

        mvc.perform(get("/api/v1/model-routes/readiness")
                        .header(AUTHORIZATION, ADMIN)
                        .header("X-Apvero-Workspace-Id", WORKSPACE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ready").value(true));

        sql.update("update model_definition set output_cost_micros_per_million = 1000000 where id = ?::uuid",
                "00000000-0000-0000-0000-000000003101");
        mvc.perform(post("/api/v1/budget-policies")
                        .header(AUTHORIZATION, ADMIN)
                        .header("X-Apvero-Workspace-Id", WORKSPACE)
                        .contentType("application/json")
                        .content("{\"name\":\"deny app two\",\"scopeType\":\"APPLICATION\",\"scopeId\":\"" + APP_TWO + "\",\"monthlyCostLimitMicros\":0}"))
                .andExpect(status().isCreated());
        Integer before = sql.queryForObject("select count(*) from execution_reservation where application_id = ?::uuid",
                Integer.class, APP_TWO);
        mvc.perform(post("/api/v1/applications/{id}/preview-runs", APP_TWO)
                        .header(AUTHORIZATION, ADMIN)
                        .header("X-Apvero-Workspace-Id", WORKSPACE)
                        .contentType("application/json")
                        .content("{\"input\":{\"message\":\"must be rejected before provider\"}}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("APVERO_BUDGET_EXCEEDED"));
        Integer after = sql.queryForObject("select count(*) from execution_reservation where application_id = ?::uuid",
                Integer.class, APP_TWO);
        assertThat(after).isEqualTo(before);

        mvc.perform(post("/api/v1/budget-policies")
                        .header(AUTHORIZATION, ADMIN)
                        .header("X-Apvero-Workspace-Id", WORKSPACE)
                        .contentType("application/json")
                        .content("{\"name\":\"rate app three\",\"scopeType\":\"APPLICATION\",\"scopeId\":\"" + APP_THREE + "\",\"requestsPerMinute\":1}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/applications/{id}/preview-runs", APP_THREE)
                        .header(AUTHORIZATION, ADMIN)
                        .header("X-Apvero-Workspace-Id", WORKSPACE)
                        .contentType("application/json")
                        .content("{\"input\":{\"message\":\"first request\"}}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/applications/{id}/preview-runs", APP_THREE)
                        .header(AUTHORIZATION, ADMIN)
                        .header("X-Apvero-Workspace-Id", WORKSPACE)
                        .contentType("application/json")
                        .content("{\"input\":{\"message\":\"second request\"}}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("APVERO_RATE_LIMIT_EXCEEDED"));

        mvc.perform(get("/api/v1/audit-events")
                        .header(AUTHORIZATION, ADMIN)
                        .header("X-Apvero-Workspace-Id", WORKSPACE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actorId").isNotEmpty())
                .andExpect(jsonPath("$[0].details").isMap());
        assertThat(sql.queryForObject("""
                select count(*) from audit_event
                where workspace_id = ?::uuid and outcome = 'DENIED'
                    and action like 'WORKSPACE_ACCESS_DENIED GET /api/v1/applications%'
                """, Integer.class, WORKSPACE)).isEqualTo(1);
        assertThat(sql.queryForObject("select count(*) from audit_event where workspace_id = ?",
                Integer.class, otherWorkspace)).isZero();

        UUID oldAuditId = UUID.randomUUID();
        sql.update("""
                insert into audit_event(id, tenant_id, workspace_id, occurred_at, actor_id, action,
                    resource_type, outcome, details)
                values (?, ?::uuid, ?::uuid, now() - interval '400 days', 'retention-test',
                    'RETENTION_TEST', 'test', 'SUCCEEDED', '{}'::jsonb)
                """, oldAuditId, "00000000-0000-0000-0000-000000000001", WORKSPACE);
        assertThatThrownBy(() -> sql.update("update audit_event set action = 'ILLEGAL' where id = ?", oldAuditId))
                .isInstanceOf(DataAccessException.class);
        assertThat(governance.purgeAuditBefore(UUID.fromString(WORKSPACE),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(365))).isEqualTo(1);

        sql.update("update ai_run set created_at = now() - interval '40 days' where id = ?::uuid",
                "10000000-0000-0000-0000-000000000001");
        assertThat(runs.purgeBefore(UUID.fromString(WORKSPACE),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(30))).isEqualTo(1);

        assertThat(sql.queryForObject("select count(*) from flyway_schema_history where success", Integer.class))
                .isGreaterThanOrEqualTo(7);
    }
}
