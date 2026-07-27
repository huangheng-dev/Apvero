package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.apvero.platform.identity.WorkspaceScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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
        "apvero.security.mode=enforced",
        "apvero.security.bootstrap-token=p22d1-test-bootstrap"
})
@AutoConfigureMockMvc
class P22d1KnowledgeIndexBuildApiIntegrationTest {
    private static final String ADMIN = "Bearer p22d1-test-bootstrap";
    private static final String WORKSPACE_HEADER = "X-Apvero-Workspace-Id";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p22d1_test")
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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate sql;

    @Test
    void closesCreateListGetIdempotencyScopeAndPermissionWorkflow() throws Exception {
        BuildFixture owner = createFixture("owner");
        WorkspaceScope outsider = createScope("outsider");
        String request = request("1.0.0", owner.routeId(), owner.revisionId());

        String firstResponse = mvc.perform(post("/api/v1/knowledge-indexes/{indexId}/builds", owner.indexId())
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, owner.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.sourceRevisionCount").value(1))
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andExpect(jsonPath("$.vectorDimension").value(3))
                .andReturn().getResponse().getContentAsString();
        UUID buildId = UUID.fromString(json.readTree(firstResponse).path("id").stringValue());

        mvc.perform(post("/api/v1/knowledge-indexes/{indexId}/builds", owner.indexId())
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, owner.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(buildId.toString()));
        assertThat(sql.queryForObject(
                "select count(*) from knowledge_index_build where id = ?", Integer.class, buildId))
                .isEqualTo(1);
        assertThat(sql.queryForObject("""
                select count(*) from audit_event
                where workspace_id = ? and action = 'knowledge.index-build.requested'
                """, Integer.class, owner.scope().workspaceId())).isEqualTo(1);

        String reader = createReader(owner.scope());
        mvc.perform(get("/api/v1/knowledge-indexes/{indexId}/builds", owner.indexId())
                        .header("Authorization", "Bearer " + reader)
                        .header(WORKSPACE_HEADER, owner.scope().workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(buildId.toString()));
        mvc.perform(get("/api/v1/knowledge-index-builds/{buildId}", buildId)
                        .header("Authorization", "Bearer " + reader)
                        .header(WORKSPACE_HEADER, owner.scope().workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddingRouteReference").value(owner.routeReference()));
        mvc.perform(post("/api/v1/knowledge-indexes/{indexId}/builds", owner.indexId())
                        .header("Authorization", "Bearer " + reader)
                        .header(WORKSPACE_HEADER, owner.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APVERO_ACCESS_DENIED"));

        mvc.perform(get("/api/v1/knowledge-index-builds/{buildId}", buildId)
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, outsider.workspaceId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APVERO_KNOWLEDGE_BUILD_NOT_FOUND"));
        mvc.perform(get("/api/v1/knowledge-indexes/{indexId}/builds", owner.indexId())
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, outsider.workspaceId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APVERO_KNOWLEDGE_INDEX_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidIneligibleUnreadyAndConflictingBuildRequests() throws Exception {
        BuildFixture fixture = createFixture("validation");

        mvc.perform(post("/api/v1/knowledge-indexes/{indexId}/builds", fixture.indexId())
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("APVERO_KNOWLEDGE_BUILD_REQUEST_INVALID"));
        mvc.perform(post("/api/v1/knowledge-indexes/{indexId}/builds", fixture.indexId())
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("latest", fixture.routeId(), fixture.revisionId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("APVERO_KNOWLEDGE_BUILD_REQUEST_INVALID"));

        sql.update("update model_provider set enabled = false where id = ?", fixture.providerId());
        mvc.perform(post("/api/v1/knowledge-indexes/{indexId}/builds", fixture.indexId())
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("1.0.0", fixture.routeId(), fixture.revisionId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APVERO_KNOWLEDGE_EMBEDDING_ROUTE_NOT_READY"));
        sql.update("update model_provider set enabled = true where id = ?", fixture.providerId());

        UUID missingRevision = UUID.randomUUID();
        mvc.perform(post("/api/v1/knowledge-indexes/{indexId}/builds", fixture.indexId())
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("1.0.0", fixture.routeId(), missingRevision)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APVERO_KNOWLEDGE_BUILD_SOURCE_INELIGIBLE"));

        mvc.perform(post("/api/v1/knowledge-indexes/{indexId}/builds", fixture.indexId())
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("1.0.0", fixture.routeId(), fixture.revisionId())))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/knowledge-indexes/{indexId}/builds", fixture.indexId())
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("1.0.0", fixture.alternateRouteId(), fixture.revisionId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APVERO_KNOWLEDGE_BUILD_VERSION_CONFLICT"));
    }

    @Test
    void persistsCancelAndManualRetryStateWithAudit() throws Exception {
        BuildFixture fixture = createFixture("commands");
        UUID cancelledId = createBuild(fixture, "1.0.0");

        mvc.perform(post("/api/v1/knowledge-index-builds/{buildId}/cancel", cancelledId)
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/knowledge-index-builds/{buildId}/cancel", cancelledId)
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APVERO_KNOWLEDGE_BUILD_NOT_CANCELLABLE"));

        UUID failedId = createBuild(fixture, "1.0.1");
        assertThat(sql.update("""
                update knowledge_index_build
                set status = 'EMBEDDING', started_at = now(),
                    lock_version = lock_version + 1, updated_at = now()
                where id = ?
                """, failedId)).isEqualTo(1);
        assertThat(sql.update("""
                update knowledge_index_build
                set status = 'FAILED', retryable = true,
                    error_code = 'APVERO_EMBEDDING_PROVIDER_UNAVAILABLE',
                    error_category = 'TRANSIENT', completed_at = now(),
                    lock_version = lock_version + 1, updated_at = now()
                where id = ?
                """, failedId)).isEqualTo(1);

        mvc.perform(post("/api/v1/knowledge-index-builds/{buildId}/retry", failedId)
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RETRY_WAIT"))
                .andExpect(jsonPath("$.errorCode").doesNotExist());

        assertThat(sql.queryForObject("""
                select count(*) from audit_event
                where workspace_id = ?
                  and action in ('knowledge.index-build.cancelled',
                                 'knowledge.index-build.retry-requested')
                """, Integer.class, fixture.scope().workspaceId())).isEqualTo(2);
    }

    @Test
    void rollsBackBuildAndRevisionWhenAuditAppendFails() {
        BuildFixture fixture = createFixture("audit-rollback");
        sql.execute("drop trigger if exists p22d1_reject_build_audit on audit_event");
        sql.execute("drop function if exists p22d1_reject_build_audit()");
        sql.execute("""
                create function p22d1_reject_build_audit() returns trigger as $$
                begin
                    if new.action = 'knowledge.index-build.requested' then
                        raise exception 'p22d1 audit failure';
                    end if;
                    return new;
                end;
                $$ language plpgsql
                """);
        sql.execute("""
                create trigger p22d1_reject_build_audit
                before insert on audit_event
                for each row execute function p22d1_reject_build_audit()
                """);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> mvc.perform(
                            post("/api/v1/knowledge-indexes/{indexId}/builds", fixture.indexId())
                                    .header("Authorization", ADMIN)
                                    .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(request("1.0.0", fixture.routeId(), fixture.revisionId()))))
                    .hasCauseInstanceOf(org.jooq.exception.DataAccessException.class);
            assertThat(sql.queryForObject(
                    "select count(*) from knowledge_index_build where knowledge_index_id = ?",
                    Integer.class, fixture.indexId())).isZero();
            assertThat(sql.queryForObject(
                    "select count(*) from knowledge_index_build_revision where knowledge_index_id = ?",
                    Integer.class, fixture.indexId())).isZero();
        } finally {
            sql.execute("drop trigger if exists p22d1_reject_build_audit on audit_event");
            sql.execute("drop function if exists p22d1_reject_build_audit()");
        }
    }

    private UUID createBuild(BuildFixture fixture, String version) throws Exception {
        String response = mvc.perform(post("/api/v1/knowledge-indexes/{indexId}/builds", fixture.indexId())
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(version, fixture.routeId(), fixture.revisionId())))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).path("id").stringValue());
    }

    private String createReader(WorkspaceScope scope) throws Exception {
        String response = mvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, scope.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"build-reader\",\"scopes\":[\"read\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = json.readTree(response);
        return body.path("plaintext").stringValue();
    }

    private BuildFixture createFixture(String label) {
        WorkspaceScope scope = createScope(label);
        String suffix = scope.workspaceId().toString().replace("-", "").substring(0, 12);
        UUID baseId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID alternateRouteId = UUID.randomUUID();
        UUID indexId = UUID.randomUUID();
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
                    convert_to('hello', 'UTF8'), 'SNAPSHOTTED',
                    'apvero-text@1.0.0', 'apvero-boundary@1.0.0', ?)
                """, revisionId, scope.tenantId(), scope.workspaceId(), sourceId, digest('1'), now);
        sql.update("""
                update knowledge_source
                set latest_revision_number = 1, latest_revision_id = ?, updated_at = ?
                where id = ?
                """, revisionId, now, sourceId);
        sql.update("""
                insert into knowledge_document(
                    id, tenant_id, workspace_id, source_revision_id, ordinal, title,
                    normalized_text_digest, parser_version, processing_profile, created_at)
                values (?, ?, ?, ?, 0, 'Document', ?,
                    'apvero-text@1.0.0', 'apvero-default@1.0.0', ?)
                """, documentId, scope.tenantId(), scope.workspaceId(), revisionId, digest('2'), now);
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
                """, providerId, scope.tenantId(), scope.workspaceId(), "Provider " + label, now, now);
        sql.update("""
                insert into model_definition(
                    id, tenant_id, workspace_id, provider_id, model_key, name,
                    capabilities, input_cost_micros_per_million,
                    output_cost_micros_per_million, enabled, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, '["EMBEDDING"]'::jsonb, 0, 0, true, ?, ?)
                """, modelId, scope.tenantId(), scope.workspaceId(), providerId,
                "model-" + suffix, "Model " + label, now, now);
        insertRoute(scope, routeId, modelId, "embedding-" + suffix, now);
        insertRoute(scope, alternateRouteId, modelId, "alternate-" + suffix, now);
        sql.update("""
                insert into knowledge_index(
                    id, tenant_id, workspace_id, knowledge_base_id, slug, name, status,
                    metadata_version, version_count, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE', 1, 0, ?, ?)
                """, indexId, scope.tenantId(), scope.workspaceId(), baseId,
                "index-" + suffix, "Index " + label, now, now);
        return new BuildFixture(
                scope,
                indexId,
                revisionId,
                providerId,
                routeId,
                "embedding-" + suffix + "@1",
                alternateRouteId);
    }

    private void insertRoute(
            WorkspaceScope scope,
            UUID routeId,
            UUID modelId,
            String routeName,
            OffsetDateTime now) {
        sql.update("""
                insert into model_route(
                    id, tenant_id, workspace_id, name, version, model_id, status,
                    timeout_ms, route_capability, embedding_dimension,
                    embedding_maximum_input_tokens, embedding_maximum_batch_size,
                    embedding_normalization, created_at)
                values (?, ?, ?, ?, 1, ?, 'PUBLISHED', 30000, 'EMBEDDING',
                    3, 8192, 64, 'NONE', ?)
                """, routeId, scope.tenantId(), scope.workspaceId(), routeName, modelId, now);
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

    private static String request(String version, UUID routeId, UUID revisionId) {
        return """
                {"version":"%s","embeddingRouteId":"%s","sourceRevisionIds":["%s"]}
                """.formatted(version, routeId, revisionId);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record BuildFixture(
            WorkspaceScope scope,
            UUID indexId,
            UUID revisionId,
            UUID providerId,
            UUID routeId,
            String routeReference,
            UUID alternateRouteId) {}
}
