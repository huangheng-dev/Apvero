package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingExecutionResult;
import io.apvero.platform.governance.ExecutionComponentState;
import io.apvero.platform.governance.ExecutionGovernance;
import io.apvero.platform.governance.ExecutionUsageQuality;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingLeaseCoordinator.AdmittedComponent;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
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
        "apvero.knowledge.index-build-runner.enabled=false",
        "apvero.knowledge.index-build-runner.lease-duration=10s",
        "apvero.knowledge.index-build-runner.external-call-timeout=2s",
        "apvero.knowledge.index-build-runner.commit-margin=1s"
})
class P22d3KnowledgeEmbeddingOrchestrationIntegrationTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p22d3_test")
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

    @Autowired KnowledgeIndexBuildTransitionKernel kernel;
    @Autowired KnowledgeIndexBuildEmbeddingOrchestrator orchestrator;
    @Autowired KnowledgeEmbeddingBatchExecutor batches;
    @Autowired KnowledgeEmbeddingLeaseCoordinator coordinator;
    @Autowired EmbeddingCapability embeddings;
    @Autowired KnowledgeIndexPersistenceRepository repository;
    @Autowired ExecutionGovernance governance;
    @Autowired JdbcTemplate sql;

    @Test
    void closesOneGovernedBatchAndAdvancesTheNextClaimToIndexing() {
        Fixture fixture = createFixture();
        BuildRow claim = kernel.claim(fixture.scope(), "d3-worker-a", 1).getFirst();

        KnowledgeEmbeddingClaimOutcome embedded =
                orchestrator.executeClaim(fixture.scope(), claim, "d3-worker-a");

        assertThat(embedded.action()).isEqualTo(RecoveryAction.DISPATCH);
        assertThat(embedded.providerInvoked()).isTrue();
        assertThat(embedded.build().embeddedEntryCount()).isEqualTo(1);
        assertThat(embedded.build().leaseOwner()).isNull();
        assertThat(repository.listEntries(fixture.scope(), fixture.buildId()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.chunkId()).isEqualTo(fixture.chunkId());
                    assertThat(entry.vectorDimension()).isEqualTo(256);
                    assertThat(entry.embedding()).hasSize(256);
                    assertThat(entry.batchOrdinal()).isZero();
                });

        UUID reservationId = sql.queryForObject("""
                select id from execution_reservation
                where tenant_id = ? and workspace_id = ?
                  and subject_type = 'KNOWLEDGE_INGESTION' and subject_id = ?
                """, UUID.class, fixture.scope().tenantId(),
                fixture.scope().workspaceId(), fixture.buildId());
        String componentIdentity = sql.queryForObject("""
                select idempotency_identity from execution_reservation_component
                where reservation_id = ?
                """, String.class, reservationId);
        assertThat(governance.findComponent(
                        fixture.scope().workspaceId(), reservationId, componentIdentity))
                .get()
                .satisfies(component -> {
                    assertThat(component.state()).isEqualTo(ExecutionComponentState.SUCCEEDED);
                    assertThat(component.actualCostMicros()).isZero();
                });

        BuildRow finalClaim =
                kernel.claim(fixture.scope(), "d3-worker-b", 1).getFirst();
        KnowledgeEmbeddingClaimOutcome indexed =
                orchestrator.executeClaim(fixture.scope(), finalClaim, "d3-worker-b");

        assertThat(indexed.action()).isEqualTo(RecoveryAction.COMPLETE);
        assertThat(indexed.providerInvoked()).isFalse();
        assertThat(indexed.build().status()).isEqualTo(BuildStatus.INDEXING);
        assertThat(indexed.build().currentStep()).isEqualTo(BuildStep.INDEXING);
    }

    @Test
    void recoversEntriesCommittedBeforeSettlementWithoutRepeatingProviderIo() {
        Fixture fixture = createFixture();
        BuildRow predecessor = kernel.claim(fixture.scope(), "d3-crash-after-entry", 1).getFirst();
        KnowledgeEmbeddingBatchPlan plan =
                batches.prepareNext(fixture.scope(), predecessor, "d3-crash-after-entry")
                        .orElseThrow();
        AdmittedComponent component = coordinator.admitAndInspect(
                fixture.scope(), predecessor, "d3-crash-after-entry", plan);
        coordinator.markDispatched(
                fixture.scope(), predecessor, "d3-crash-after-entry", component, plan);
        EmbeddingExecutionResult result = embeddings.embed(plan.executionRequest());
        coordinator.persist(
                fixture.scope(), predecessor, "d3-crash-after-entry", plan, result);

        expireLease(fixture.buildId());
        BuildRow successor = kernel.claim(fixture.scope(), "d3-recovery-entry", 1).getFirst();
        assertThatThrownBy(() -> coordinator.persist(
                        fixture.scope(), predecessor, "d3-crash-after-entry", plan, result))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT");

        KnowledgeEmbeddingClaimOutcome recovered =
                orchestrator.executeClaim(fixture.scope(), successor, "d3-recovery-entry");

        assertThat(recovered.action()).isEqualTo(RecoveryAction.SETTLE_ONLY);
        assertThat(recovered.providerInvoked()).isFalse();
        assertThat(recovered.build().embeddedEntryCount()).isEqualTo(1);
        assertThat(repository.listEntries(fixture.scope(), fixture.buildId())).hasSize(1);
        assertComponentState(fixture, ExecutionComponentState.SUCCEEDED);
    }

    @Test
    void recoversSettlementCommittedBeforeProgressWithoutRepeatingProviderIo() {
        Fixture fixture = createFixture();
        BuildRow predecessor =
                kernel.claim(fixture.scope(), "d3-crash-after-settlement", 1).getFirst();
        KnowledgeEmbeddingBatchPlan plan =
                batches.prepareNext(fixture.scope(), predecessor, "d3-crash-after-settlement")
                        .orElseThrow();
        AdmittedComponent component = coordinator.admitAndInspect(
                fixture.scope(), predecessor, "d3-crash-after-settlement", plan);
        coordinator.markDispatched(
                fixture.scope(), predecessor, "d3-crash-after-settlement", component, plan);
        EmbeddingExecutionResult result = embeddings.embed(plan.executionRequest());
        coordinator.persist(
                fixture.scope(), predecessor, "d3-crash-after-settlement", plan, result);
        coordinator.settle(
                fixture.scope(),
                predecessor,
                "d3-crash-after-settlement",
                component,
                plan,
                plan.estimatedInputUnits(),
                plan.quote().estimatedCostMicros(),
                ExecutionUsageQuality.ESTIMATED);

        expireLease(fixture.buildId());
        BuildRow successor = kernel.claim(
                fixture.scope(), "d3-recovery-settlement", 1).getFirst();
        KnowledgeEmbeddingClaimOutcome recovered = orchestrator.executeClaim(
                fixture.scope(), successor, "d3-recovery-settlement");

        assertThat(recovered.action()).isEqualTo(RecoveryAction.COMPLETE);
        assertThat(recovered.providerInvoked()).isFalse();
        assertThat(recovered.build().embeddedEntryCount()).isEqualTo(1);
        assertThat(repository.listEntries(fixture.scope(), fixture.buildId())).hasSize(1);
        assertComponentState(fixture, ExecutionComponentState.SUCCEEDED);
    }

    private void assertComponentState(Fixture fixture, ExecutionComponentState expected) {
        UUID reservationId = sql.queryForObject("""
                select id from execution_reservation
                where tenant_id = ? and workspace_id = ?
                  and subject_type = 'KNOWLEDGE_INGESTION' and subject_id = ?
                """, UUID.class, fixture.scope().tenantId(),
                fixture.scope().workspaceId(), fixture.buildId());
        String identity = sql.queryForObject("""
                select idempotency_identity from execution_reservation_component
                where reservation_id = ?
                """, String.class, reservationId);
        assertThat(governance.findComponent(
                        fixture.scope().workspaceId(), reservationId, identity))
                .get()
                .extracting(component -> component.state())
                .isEqualTo(expected);
    }

    private void expireLease(UUID buildId) {
        sql.update("""
                update knowledge_index_build
                set lease_until = transaction_timestamp(),
                    lock_version = lock_version + 1,
                    updated_at = transaction_timestamp()
                where id = ?
                """, buildId);
    }

    private Fixture createFixture() {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        WorkspaceScope scope = new WorkspaceScope(tenantId, workspaceId);
        UUID baseId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID indexId = UUID.randomUUID();
        UUID buildId = UUID.randomUUID();
        String suffix = workspaceId.toString().replace("-", "").substring(0, 12);
        String routeName = "d3-route-" + suffix;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        sql.update("insert into tenant(id, slug, name, created_at) values (?, ?, ?, ?)",
                tenantId, "t-" + suffix, "D3 Tenant", now);
        sql.update("""
                insert into workspace(id, tenant_id, slug, name, created_at)
                values (?, ?, ?, ?, ?)
                """, workspaceId, tenantId, "w-" + suffix, "D3 Workspace", now);
        sql.update("""
                insert into knowledge_base(
                    id, tenant_id, workspace_id, slug, name, description, status,
                    version, created_at, updated_at)
                values (?, ?, ?, ?, 'D3 Base', '', 'ACTIVE', 1, ?, ?)
                """, baseId, tenantId, workspaceId, "base-" + suffix, now, now);
        sql.update("""
                insert into knowledge_source(
                    id, tenant_id, workspace_id, knowledge_base_id, name, source_type,
                    status, latest_revision_number, version, created_at, updated_at)
                values (?, ?, ?, ?, 'D3 Source', 'TEXT', 'ACTIVE', 0, 1, ?, ?)
                """, sourceId, tenantId, workspaceId, baseId, now, now);
        sql.update("""
                insert into knowledge_source_revision(
                    id, tenant_id, workspace_id, source_id, revision, content_digest,
                    media_type, byte_size, capture_metadata, snapshot_bytes, snapshot_status,
                    parser_version, chunker_version, created_at)
                values (?, ?, ?, ?, 1, ?, 'text/plain', 5, '{}'::jsonb,
                    convert_to('hello', 'UTF8'), 'SNAPSHOTTED',
                    'apvero-text@1.0.0', 'apvero-boundary@1.0.0', ?)
                """, revisionId, tenantId, workspaceId, sourceId, digest("source"), now);
        sql.update("""
                insert into knowledge_document(
                    id, tenant_id, workspace_id, source_revision_id, ordinal, title,
                    normalized_text_digest, parser_version, processing_profile, created_at)
                values (?, ?, ?, ?, 0, 'D3 Document', ?,
                    'apvero-text@1.0.0', 'apvero-default@1.0.0', ?)
                """, documentId, tenantId, workspaceId, revisionId, digest("hello"), now);
        sql.update("""
                insert into knowledge_chunk(
                    id, tenant_id, workspace_id, source_revision_id, document_id,
                    ordinal, text, content_digest, start_offset, end_offset,
                    paragraph_number, line_start, line_end, chunker_version, created_at)
                values (?, ?, ?, ?, ?, 0, 'hello', ?, 0, 5, 1, 1, 1,
                    'apvero-boundary@1.0.0', ?)
                """, chunkId, tenantId, workspaceId, revisionId,
                documentId, digest("hello"), now);
        sql.update("""
                insert into knowledge_ingestion_job(
                    id, tenant_id, workspace_id, knowledge_base_id, source_id,
                    source_revision_id, job_kind, status, current_step, sync_outcome,
                    attempt_count, maximum_attempts, lock_version, idempotency_key,
                    retryable, failure_metadata, cancellation_requested,
                    started_at, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'CREATE_SOURCE', 'READY', 'COMPLETE', 'CHANGED',
                    1, 3, 1, ?, false, '{}'::jsonb, false, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, workspaceId, baseId, sourceId, revisionId,
                "ready-" + suffix, now, now, now, now);
        sql.update("""
                insert into model_provider(
                    id, tenant_id, workspace_id, name, provider_type, base_url,
                    enabled, version, created_at, updated_at)
                values (?, ?, ?, 'D3 Deterministic', 'DETERMINISTIC_LOCAL',
                    'local://deterministic', true, 1, ?, ?)
                """, providerId, tenantId, workspaceId, now, now);
        sql.update("""
                insert into model_definition(
                    id, tenant_id, workspace_id, provider_id, model_key, name,
                    capabilities, input_cost_micros_per_million,
                    output_cost_micros_per_million, enabled, created_at, updated_at)
                values (?, ?, ?, ?, 'apvero-deterministic-embedding@1.0.0',
                    'D3 Model', '["EMBEDDING"]'::jsonb, 0, 0, true, ?, ?)
                """, modelId, tenantId, workspaceId, providerId, now, now);
        sql.update("""
                insert into model_route(
                    id, tenant_id, workspace_id, name, version, model_id, status,
                    timeout_ms, route_capability, embedding_dimension,
                    embedding_maximum_input_tokens, embedding_maximum_batch_size,
                    embedding_normalization, created_at)
                values (?, ?, ?, ?, 1, ?, 'PUBLISHED', 2000, 'EMBEDDING',
                    256, 8192, 64, 'L2', ?)
                """, routeId, tenantId, workspaceId, routeName, modelId, now);
        sql.update("""
                insert into knowledge_index(
                    id, tenant_id, workspace_id, knowledge_base_id, slug, name, status,
                    metadata_version, version_count, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'D3 Index', 'ACTIVE', 1, 0, ?, ?)
                """, indexId, tenantId, workspaceId, baseId, "index-" + suffix, now, now);

        BuildRow build = repository.insertBuild(scope, new BuildRow(
                buildId, tenantId, workspaceId, indexId, baseId, "1.0.0",
                routeId, routeName + "@1", 256, 8192, 64, "L2",
                digest("request"), digest("source-set"), 1, 1,
                BuildStatus.QUEUED, BuildStep.EMBEDDING, 0, 3, false,
                null, null, null, 1, false, 0, 0, null,
                null, null, null, null, null, false, "{}",
                null, null, now, now));
        repository.insertBuildRevision(scope, new BuildRevisionRow(
                UUID.randomUUID(), tenantId, workspaceId, build.id(), indexId, baseId,
                sourceId, revisionId, digest("source"), "apvero-text@1.0.0",
                "apvero-boundary@1.0.0", 0, now));
        return new Fixture(scope, build.id(), chunkId);
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(WorkspaceScope scope, UUID buildId, UUID chunkId) {}
}
