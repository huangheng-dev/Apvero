package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingExecutionRequest;
import io.apvero.platform.capability.EmbeddingExecutionResult;
import io.apvero.platform.capability.EmbeddingInput;
import io.apvero.platform.governance.ExecutionComponentState;
import io.apvero.platform.governance.RetentionPolicy;
import io.apvero.platform.governance.RetentionPolicyCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = WebEnvironment.MOCK, properties = {
        "apvero.knowledge.enabled=true",
        "apvero.knowledge.runner.enabled=false",
        "apvero.knowledge.index-build-runner.enabled=false",
        "apvero.security.mode=enforced",
        "apvero.security.bootstrap-token=p22e4-test-bootstrap"
})
@AutoConfigureMockMvc
class P22e3GovernedRetrievalExecutionIntegrationTest {
    private static final int DIMENSION = 256;
    private static final String QUERY = "How do I verify governed retrieval?";
    private static final String ADMIN = "Bearer p22e4-test-bootstrap";
    private static final String WORKSPACE_HEADER = "X-Apvero-Workspace-Id";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p22e3_test")
            .withUsername("apvero")
            .withPassword("apvero")
            .withStartupTimeout(Duration.ofMinutes(3));

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

    @Autowired GovernedKnowledgeRetrievalExecutor executor;
    @Autowired KnowledgeIndexPersistenceRepository repository;
    @Autowired RetentionPolicyCatalog retentionPolicies;
    @Autowired EmbeddingCapability embeddings;
    @Autowired JdbcTemplate sql;
    @Autowired MeterRegistry meters;
    @Autowired MockMvc mvc;
    @Autowired TransactionTemplate transactions;

    @Test
    void localDeterministicQueryClosesQuoteAdmissionDispatchSettlementAndRanking() {
        Fixture fixture = createPublishedFixture();
        KnowledgeCommandContext context =
                new KnowledgeCommandContext("integration-user", "127.0.0.1", "p22e3-query-1");

        GovernedRetrievalExecution result = executor.execute(
                fixture.scope().workspaceId(),
                context,
                fixture.versionId(),
                fixture.policyId(),
                "  " + QUERY + "  ");

        assertThat(result.queryDigest()).isEqualTo(KnowledgeCanonicalDigests.text(QUERY));
        assertThat(result.rankedCandidates())
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.chunkId()).isEqualTo(fixture.chunkId());
                    assertThat(hit.rank()).isEqualTo(1);
                    assertThat(hit.score().doubleValue()).isCloseTo(
                            1.0, org.assertj.core.data.Offset.offset(0.000001));
                });
        assertThat(result.providerLatencyMillis()).isGreaterThanOrEqualTo(0);

        assertThat(sql.queryForObject("""
                select count(*)
                from execution_reservation
                where tenant_id = ? and workspace_id = ?
                  and subject_type = 'KNOWLEDGE_QUERY' and status = 'SUCCEEDED'
                """, Integer.class, fixture.scope().tenantId(), fixture.scope().workspaceId()))
                .isEqualTo(1);
        assertThat(sql.queryForMap("""
                select component_type, status, usage_quality, actual_units,
                    actual_cost_micros, provider_request_identity
                from execution_reservation_component
                where tenant_id = ? and workspace_id = ?
                """, fixture.scope().tenantId(), fixture.scope().workspaceId()))
                .containsEntry("component_type", "EMBEDDING_QUERY")
                .containsEntry("status", ExecutionComponentState.SUCCEEDED.name())
                .containsEntry("usage_quality", "ESTIMATED")
                .containsEntry("actual_cost_micros", 0L)
                .containsEntry("provider_request_identity", null);

        assertThatThrownBy(() -> executor.execute(
                        fixture.scope().workspaceId(),
                        context,
                        fixture.versionId(),
                        fixture.policyId(),
                        QUERY))
                .isInstanceOf(KnowledgeException.class)
                .satisfies(error -> assertThat(((KnowledgeException) error).code())
                        .isEqualTo("APVERO_KNOWLEDGE_QUERY_ALREADY_SETTLED"));
        assertThat(sql.queryForObject(
                "select count(*) from execution_reservation",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void restBoundaryRequiresWritePermissionAndAppliesCurrentRetention() throws Exception {
        Fixture fixture = createPublishedFixture();
        WorkspaceScope outsider = createScope();
        String reader = createReader(fixture.scope());
        String body = """
                {
                  "indexVersionId": "%s",
                  "retrievalPolicyVersionId": "%s",
                  "query": "%s"
                }
                """.formatted(fixture.versionId(), fixture.policyId(), QUERY);

        mvc.perform(post("/api/v1/knowledge-retrieval-tests")
                        .header("Authorization", "Bearer " + reader)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APVERO_ACCESS_DENIED"));

        mvc.perform(post("/api/v1/knowledge-retrieval-tests")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("APVERO_KNOWLEDGE_IDENTIFIER_INVALID"));

        mvc.perform(post("/api/v1/knowledge-retrieval-tests")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                        .header("X-Request-Id", "p22e4-rest-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATCHES"))
                .andExpect(jsonPath("$.indexVersionId").value(fixture.versionId().toString()))
                .andExpect(jsonPath("$.retrievalPolicyVersionId")
                        .value(fixture.policyId().toString()))
                .andExpect(jsonPath("$.queryDigest").value(KnowledgeCanonicalDigests.text(QUERY)))
                .andExpect(jsonPath("$.hits.length()").value(1))
                .andExpect(jsonPath("$.hits[0].rank").value(1))
                .andExpect(jsonPath("$.hits[0].chunkId").value(fixture.chunkId().toString()))
                .andExpect(jsonPath("$.hits[0].content").doesNotExist())
                .andExpect(jsonPath("$.hits[0].sourceTitle").value("Runbook"))
                .andExpect(jsonPath("$.hits[0].heading").value("Verification"))
                .andExpect(jsonPath("$.hits[0].startOffset").doesNotExist())
                .andExpect(jsonPath("$.hits[0].sourceUrl").doesNotExist())
                .andExpect(jsonPath("$.hits[0].providerRequestId").doesNotExist())
                .andExpect(jsonPath("$.latencyMs").isNumber());

        mvc.perform(post("/api/v1/knowledge-retrieval-tests")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, outsider.workspaceId())
                        .header("X-Request-Id", "p22e5-cross-scope-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND"));
        assertThat(sql.queryForObject("""
                select count(*) from execution_reservation
                where workspace_id = ? and subject_type = 'KNOWLEDGE_QUERY'
                """, Integer.class, outsider.workspaceId())).isZero();

        OffsetDateTime tombstonedAt = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(sql.update("""
                update knowledge_source
                set status = 'TOMBSTONED', tombstoned_at = ?,
                    tombstoned_by = 'p22e5-history',
                    version = version + 1, updated_at = ?
                where tenant_id = ? and workspace_id = ? and id = ?
                """, tombstonedAt, tombstonedAt, fixture.scope().tenantId(),
                fixture.scope().workspaceId(), fixture.sourceId())).isEqualTo(1);
        mvc.perform(post("/api/v1/knowledge-retrieval-tests")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, fixture.scope().workspaceId())
                        .header("X-Request-Id", "p22e5-history-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATCHES"))
                .andExpect(jsonPath("$.hits[0].chunkId").value(fixture.chunkId().toString()));

        assertThat(sql.queryForObject("""
                select count(*) from execution_reservation
                where workspace_id = ? and subject_type = 'KNOWLEDGE_QUERY'
                """, Integer.class, fixture.scope().workspaceId())).isEqualTo(2);
        assertThat(meters.get("apvero.knowledge.retrieval.request")
                        .tag("outcome", "matches")
                        .tag("failure_family", "none")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(2);
        assertThat(meters.get("apvero.knowledge.retrieval.request")
                        .tag("outcome", "failed")
                        .tag("failure_family", "not_found")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1);
        assertThat(meters.get("apvero.knowledge.retrieval.provider.latency")
                        .timer()
                        .count())
                .isGreaterThanOrEqualTo(2);
    }

    private String createReader(WorkspaceScope scope) throws Exception {
        String response = mvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", ADMIN)
                        .header(WORKSPACE_HEADER, scope.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"retrieval-reader\",\"scopes\":[\"read\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper()
                .readTree(response)
                .path("plaintext")
                .stringValue();
    }

    private Fixture createPublishedFixture() {
        WorkspaceScope scope = createScope();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID baseId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID routeId = createEmbeddingRoute(scope, suffix, now);
        String routeReference = "retrieval-" + suffix + "@1";

        sql.update("""
                insert into knowledge_base(
                    id, tenant_id, workspace_id, slug, name, description, status,
                    version, created_at, updated_at)
                values (?, ?, ?, ?, 'Retrieval', '', 'ACTIVE', 1, ?, ?)
                """, baseId, scope.tenantId(), scope.workspaceId(),
                "base-" + suffix, now, now);
        sql.update("""
                insert into knowledge_source(
                    id, tenant_id, workspace_id, knowledge_base_id, name, source_type,
                    status, latest_revision_number, version, created_at, updated_at)
                values (?, ?, ?, ?, 'Runbook', 'MARKDOWN', 'ACTIVE', 0, 1, ?, ?)
                """, sourceId, scope.tenantId(), scope.workspaceId(), baseId, now, now);
        byte[] snapshot = QUERY.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        sql.update("""
                insert into knowledge_source_revision(
                    id, tenant_id, workspace_id, source_id, revision, content_digest,
                    media_type, byte_size, capture_metadata, snapshot_bytes, snapshot_status,
                    parser_version, chunker_version, created_at)
                values (?, ?, ?, ?, 1, ?, 'text/markdown', ?, '{}'::jsonb,
                    ?, 'SNAPSHOTTED', 'apvero-text@1.0.0', 'apvero-boundary@1.0.0', ?)
                """, revisionId, scope.tenantId(), scope.workspaceId(), sourceId,
                KnowledgeCanonicalDigests.bytes(snapshot), snapshot.length, snapshot, now);
        sql.update("""
                insert into knowledge_document(
                    id, tenant_id, workspace_id, source_revision_id, ordinal, title,
                    normalized_text_digest, parser_version, processing_profile, created_at)
                values (?, ?, ?, ?, 0, 'Governed retrieval', ?,
                    'apvero-text@1.0.0', 'apvero-default@1.0.0', ?)
                """, documentId, scope.tenantId(), scope.workspaceId(), revisionId,
                KnowledgeCanonicalDigests.text(QUERY), now);
        sql.update("""
                insert into knowledge_chunk(
                    id, tenant_id, workspace_id, source_revision_id, document_id,
                    ordinal, text, content_digest, start_offset, end_offset,
                    heading, paragraph_number, line_start, line_end,
                    chunker_version, created_at)
                values (?, ?, ?, ?, ?, 0, ?, ?, 0, ?, 'Verification', 1, 1, 1,
                    'apvero-boundary@1.0.0', ?)
                """, chunkId, scope.tenantId(), scope.workspaceId(), revisionId,
                documentId, QUERY, KnowledgeCanonicalDigests.text(QUERY),
                QUERY.length(), now);
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

        String queryHex = KnowledgeCanonicalDigests.text(QUERY).substring("sha256:".length());
        UUID inputId = UUID.randomUUID();
        EmbeddingExecutionResult vectorResult = embeddings.embed(new EmbeddingExecutionRequest(
                scope.workspaceId(), routeReference, "fixture-vector-" + suffix,
                List.of(new EmbeddingInput(inputId, queryHex, QUERY))));
        List<Float> vector = vectorResult.orderedOutputs().getFirst().vector();

        IndexRow index = repository.insertIndex(scope, new IndexRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), baseId,
                "index-" + suffix, "Retrieval Index", IndexStatus.ACTIVE,
                1, 0, null, now, now));
        BuildRow queued = repository.insertBuild(scope, new BuildRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(),
                index.id(), baseId, "1.0.0", routeId, routeReference,
                DIMENSION, 8192, 64, "L2", digest('1'), digest('2'), 1, 1,
                BuildStatus.QUEUED, BuildStep.EMBEDDING, 0, 3, false,
                null, null, null, 1, false, 0, 0, null, null, null, null,
                null, null, false, "{}", null, null, now, now));
        repository.insertBuildRevision(scope, new BuildRevisionRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(),
                queued.id(), index.id(), baseId, sourceId, revisionId,
                KnowledgeCanonicalDigests.bytes(snapshot),
                "apvero-text@1.0.0", "apvero-boundary@1.0.0", 0, now));
        sql.update("""
                update knowledge_index_build
                set status = 'EMBEDDING', started_at = ?,
                    lock_version = lock_version + 1, updated_at = ?
                where id = ?
                """, now, now, queued.id());
        repository.insertEntry(scope, new EntryRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(),
                queued.id(), index.id(), baseId, sourceId, revisionId, documentId,
                chunkId, 0, vector, DIMENSION, KnowledgeCanonicalDigests.vector(vector),
                KnowledgeCanonicalDigests.text(QUERY), 0, routeId, routeReference, now));

        UUID versionId = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> {
            sql.update("""
                    update knowledge_index_build
                    set status = 'INDEXING', current_step = 'INDEXING',
                        embedded_entry_count = 1, last_durable_chunk_ordinal = 0,
                        lock_version = lock_version + 1, updated_at = ?
                    where id = ?
                    """, now, queued.id());
            sql.update("""
                    update knowledge_index_build
                    set status = 'VALIDATING', current_step = 'VALIDATING',
                        validated_entry_count = 1, validation_digest = ?, artifact_digest = ?,
                        lock_version = lock_version + 1, updated_at = ?
                    where id = ?
                    """, digest('3'), digest('4'), now, queued.id());
            repository.insertVersion(scope, new VersionRow(
                    versionId, scope.tenantId(), scope.workspaceId(), index.id(), queued.id(),
                    "1.0.0", "index-" + suffix + "@1.0.0", routeId, routeReference,
                    DIMENSION, 1, 1, digest('4'), "READY", now));
            sql.update("""
                    update knowledge_index_build
                    set status = 'READY', current_step = 'COMPLETE',
                        published_version_id = ?, completed_at = ?,
                        lock_version = lock_version + 1, updated_at = ?
                    where id = ?
                    """, versionId, now, now, queued.id());
            sql.update("""
                    update knowledge_index
                    set latest_ready_version_id = ?, version_count = 1,
                        metadata_version = metadata_version + 1, updated_at = ?
                    where id = ?
                    """, versionId, now, index.id());
        });

        RetentionPolicy retention = retentionPolicies.getOrCreate(scope.workspaceId());
        UUID policyId = UUID.randomUUID();
        BigDecimal minimumScore = new BigDecimal("0.500000");
        String policyDigest = RetrievalPolicyDigests.canonical(
                DefaultRetrievalPolicyVersionCatalog.RETRIEVAL_ALGORITHM_VERSION,
                DefaultRetrievalPolicyVersionCatalog.TOKEN_ESTIMATOR_VERSION,
                retention.version(),
                5,
                4096,
                minimumScore,
                "KEEP",
                "NO_EVIDENCE");
        repository.insertPolicy(scope, new RetrievalPolicyRow(
                policyId, scope.tenantId(), scope.workspaceId(),
                "governed", "1.0.0", "exact-cosine@1.0.0",
                "apvero-utf8-byte@1.0.0", retention.version(), 5, 4096,
                minimumScore, "KEEP", "NO_EVIDENCE",
                policyDigest, "integration-user", now));
        return new Fixture(scope, sourceId, versionId, policyId, chunkId);
    }

    private UUID createEmbeddingRoute(
            WorkspaceScope scope,
            String suffix,
            OffsetDateTime now) {
        UUID providerId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        sql.update("""
                insert into model_provider(
                    id, tenant_id, workspace_id, name, provider_type, base_url,
                    enabled, version, created_at, updated_at)
                values (?, ?, ?, 'Deterministic', 'DETERMINISTIC_LOCAL',
                    'local://deterministic', true, 1, ?, ?)
                """, providerId, scope.tenantId(), scope.workspaceId(), now, now);
        sql.update("""
                insert into model_definition(
                    id, tenant_id, workspace_id, provider_id, model_key, name,
                    capabilities, input_cost_micros_per_million,
                    output_cost_micros_per_million, enabled, created_at, updated_at)
                values (?, ?, ?, ?, 'deterministic-embedding', 'Deterministic Embedding',
                    '["EMBEDDING"]'::jsonb, 0, 0, true, ?, ?)
                """, modelId, scope.tenantId(), scope.workspaceId(), providerId, now, now);
        sql.update("""
                insert into model_route(
                    id, tenant_id, workspace_id, name, version, model_id, status,
                    timeout_ms, route_capability, embedding_dimension,
                    embedding_maximum_input_tokens, embedding_maximum_batch_size,
                    embedding_normalization, created_at)
                values (?, ?, ?, ?, 1, ?, 'PUBLISHED', 30000, 'EMBEDDING',
                    256, 8192, 64, 'L2', ?)
                """, routeId, scope.tenantId(), scope.workspaceId(),
                "retrieval-" + suffix, modelId, now);
        return routeId;
    }

    private WorkspaceScope createScope() {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String suffix = tenantId.toString().replace("-", "").substring(0, 12);
        sql.update("insert into tenant(id, slug, name, created_at) values (?, ?, ?, now())",
                tenantId, "t-" + suffix, "Tenant");
        sql.update("""
                insert into workspace(id, tenant_id, slug, name, created_at)
                values (?, ?, ?, 'Workspace', now())
                """, workspaceId, tenantId, "w-" + suffix);
        return new WorkspaceScope(tenantId, workspaceId);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(
            WorkspaceScope scope,
            UUID sourceId,
            UUID versionId,
            UUID policyId,
            UUID chunkId) {}
}
