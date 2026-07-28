package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "apvero.knowledge.enabled=true",
        "apvero.knowledge.runner.enabled=false",
        "apvero.knowledge.index-build-runner.enabled=false",
        "apvero.knowledge.index-build-runner.claim-batch=4",
        "apvero.knowledge.index-build-runner.lease-duration=2s",
        "apvero.knowledge.index-build-runner.external-call-timeout=1s",
        "apvero.knowledge.index-build-runner.commit-margin=500ms",
        "apvero.knowledge.index-build-runner.backoff-base=10ms",
        "apvero.knowledge.index-build-runner.backoff-maximum=20ms"
})
class P22d2KnowledgeIndexBuildLeaseIntegrationTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p22d2_test")
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
    @Autowired KnowledgeIndexPersistenceRepository repository;
    @Autowired JdbcTemplate sql;
    @Autowired TransactionTemplate transactions;

    @Test
    void claimsDeterministicallyWithinScopeAndLeavesOtherWorkspaceInvisible() {
        WorkspaceFixture owner = createWorkspace("scope-owner");
        WorkspaceFixture outsider = createWorkspace("scope-outsider");
        UUID first = insertBuild(owner, 1, 3, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2));
        UUID second = insertBuild(owner, 1, 3, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        UUID hidden = insertBuild(outsider, 1, 3, OffsetDateTime.now(ZoneOffset.UTC));

        List<BuildRow> firstClaim = kernel.claim(owner.scope(), "worker-a", 1);
        List<BuildRow> secondClaim = kernel.claim(owner.scope(), "worker-b", 4);

        assertThat(firstClaim).extracting(BuildRow::id).containsExactly(first);
        assertThat(firstClaim.getFirst().status()).isEqualTo(BuildStatus.EMBEDDING);
        assertThat(firstClaim.getFirst().attemptCount()).isEqualTo(1);
        assertThat(firstClaim.getFirst().lockVersion()).isEqualTo(2);
        assertThat(secondClaim).extracting(BuildRow::id).containsExactly(second);
        assertThat(kernel.claim(owner.scope(), "worker-c", 4)).isEmpty();
        assertThat(kernel.claim(outsider.scope(), "worker-d", 4))
                .extracting(BuildRow::id)
                .containsExactly(hidden);
    }

    @Test
    void oneConcurrentClaimWinsAndRollbackLeavesBuildClaimable() throws Exception {
        WorkspaceFixture fixture = createWorkspace("concurrent");
        UUID buildId = insertBuild(fixture, 1, 3, OffsetDateTime.now(ZoneOffset.UTC));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<BuildRow>> first = executor.submit(() -> {
                start.await();
                return kernel.claim(fixture.scope(), "parallel-a", 1);
            });
            Future<List<BuildRow>> second = executor.submit(() -> {
                start.await();
                return kernel.claim(fixture.scope(), "parallel-b", 1);
            });
            start.countDown();

            assertThat(first.get().size() + second.get().size()).isEqualTo(1);
        }

        WorkspaceFixture rollbackFixture = createWorkspace("rollback");
        UUID rollbackBuild = insertBuild(
                rollbackFixture, 1, 3, OffsetDateTime.now(ZoneOffset.UTC));
        transactions.executeWithoutResult(status -> {
            assertThat(kernel.claim(rollbackFixture.scope(), "rollback-owner", 1))
                    .extracting(BuildRow::id)
                    .containsExactly(rollbackBuild);
            status.setRollbackOnly();
        });

        assertThat(kernel.claim(rollbackFixture.scope(), "after-rollback", 1))
                .extracting(BuildRow::id)
                .containsExactly(rollbackBuild);
        assertThat(repository.findBuild(fixture.scope(), buildId)).isPresent();
    }

    @Test
    void expiredLeaseCanBeReclaimedAndFencesThePredecessor() {
        WorkspaceFixture fixture = createWorkspace("stale");
        UUID buildId = insertBuild(fixture, 1, 3, OffsetDateTime.now(ZoneOffset.UTC));
        BuildRow predecessor = kernel.claim(fixture.scope(), "worker-old", 1).getFirst();
        assertThat(kernel.requireActiveLease(
                fixture.scope(), predecessor, "worker-old")).isEqualTo(predecessor);
        expireLeaseAtDatabaseBoundary(buildId);

        BuildRow successor = kernel.claim(fixture.scope(), "worker-new", 1).getFirst();

        assertThat(successor.id()).isEqualTo(buildId);
        assertThat(successor.attemptCount()).isEqualTo(predecessor.attemptCount());
        assertThat(successor.lockVersion()).isGreaterThan(predecessor.lockVersion());
        assertThatThrownBy(() -> kernel.renew(fixture.scope(), predecessor, "worker-old"))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT");
        assertThatThrownBy(() -> kernel.requireActiveLease(
                        fixture.scope(), predecessor, "worker-old"))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT");
        assertThatThrownBy(() -> kernel.recordEmbeddingProgressAndRelease(
                        fixture.scope(), predecessor, "worker-old", 1, 0))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT");
        assertThatThrownBy(() -> kernel.recordFailure(
                        fixture.scope(),
                        predecessor,
                        "worker-old",
                        new KnowledgeIndexBuildFailure(
                                "APVERO_TEST_STALE",
                                KnowledgeIndexBuildFailure.Category.TRANSIENT,
                                true,
                                false)))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT");
        BuildRow renewed = kernel.renew(fixture.scope(), successor, "worker-new");
        assertThat(renewed.lockVersion()).isEqualTo(successor.lockVersion() + 1);
        assertThat(renewed.leaseUntil()).isAfter(successor.updatedAt());
    }

    @Test
    void persistsProgressAndOnlyAllowsCompleteForwardTransitions() {
        WorkspaceFixture fixture = createWorkspace("transitions");
        UUID buildId = insertBuild(fixture, 2, 3, OffsetDateTime.now(ZoneOffset.UTC));
        BuildRow firstClaim = kernel.claim(fixture.scope(), "step-a", 1).getFirst();

        BuildRow progress = kernel.recordEmbeddingProgressAndRelease(
                fixture.scope(), firstClaim, "step-a", 1, 0);
        assertThat(progress.embeddedEntryCount()).isEqualTo(1);
        assertThat(progress.leaseOwner()).isNull();
        assertThatThrownBy(() -> kernel.advanceToIndexingAndRelease(
                        fixture.scope(), firstClaim, "step-a"))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_STATE_CONFLICT");

        BuildRow secondClaim = kernel.claim(fixture.scope(), "step-b", 1).getFirst();
        BuildRow completedEmbedding = kernel.recordEmbeddingProgressAndRelease(
                fixture.scope(), secondClaim, "step-b", 2, 1);
        BuildRow transitionClaim = kernel.claim(fixture.scope(), "step-c", 1).getFirst();
        BuildRow indexing = kernel.advanceToIndexingAndRelease(
                fixture.scope(), transitionClaim, "step-c");

        assertThat(completedEmbedding.lockVersion()).isLessThan(indexing.lockVersion());
        assertThat(indexing.status()).isEqualTo(BuildStatus.INDEXING);
        BuildRow indexingClaim = kernel.claim(fixture.scope(), "step-d", 1).getFirst();
        BuildRow validating = kernel.advanceToValidatingAndRelease(
                fixture.scope(), indexingClaim, "step-d", 2, digest('9'));
        assertThat(validating.status()).isEqualTo(BuildStatus.VALIDATING);
        assertThat(validating.validatedEntryCount()).isEqualTo(2);
        assertThat(validating.validationDigest()).isEqualTo(digest('9'));
    }

    @Test
    void retryPermanentExhaustedAndAmbiguousFailuresHaveExactDurableShapes() {
        WorkspaceFixture retryFixture = createWorkspace("retry");
        UUID retryId = insertBuild(retryFixture, 1, 3, OffsetDateTime.now(ZoneOffset.UTC));
        BuildRow retryClaim = kernel.claim(retryFixture.scope(), "retry-a", 1).getFirst();
        BuildRow waiting = kernel.recordFailure(
                retryFixture.scope(),
                retryClaim,
                "retry-a",
                new KnowledgeIndexBuildFailure(
                        "APVERO_TEST_TRANSIENT",
                        KnowledgeIndexBuildFailure.Category.TRANSIENT,
                        true,
                        false));
        assertThat(waiting.status()).isEqualTo(BuildStatus.RETRY_WAIT);
        assertThat(waiting.nextAttemptAt()).isAfter(waiting.updatedAt());
        assertThat(waiting.completedAt()).isNull();
        assertThat(kernel.claim(retryFixture.scope(), "too-early", 1)).isEmpty();
        makeRetryDue(retryId);
        assertThat(kernel.claim(retryFixture.scope(), "retry-b", 1).getFirst().attemptCount())
                .isEqualTo(2);

        WorkspaceFixture permanentFixture = createWorkspace("permanent");
        insertBuild(permanentFixture, 1, 3, OffsetDateTime.now(ZoneOffset.UTC));
        BuildRow permanentClaim =
                kernel.claim(permanentFixture.scope(), "permanent-owner", 1).getFirst();
        BuildRow permanent = kernel.recordFailure(
                permanentFixture.scope(),
                permanentClaim,
                "permanent-owner",
                new KnowledgeIndexBuildFailure(
                        "APVERO_TEST_PERMANENT",
                        KnowledgeIndexBuildFailure.Category.PERMANENT,
                        false,
                        false));
        assertThat(permanent.status()).isEqualTo(BuildStatus.FAILED);
        assertThat(permanent.retryable()).isFalse();
        assertThat(permanent.completedAt()).isNotNull();

        WorkspaceFixture exhaustedFixture = createWorkspace("exhausted");
        insertBuild(exhaustedFixture, 1, 1, OffsetDateTime.now(ZoneOffset.UTC));
        BuildRow exhaustedClaim =
                kernel.claim(exhaustedFixture.scope(), "exhausted-owner", 1).getFirst();
        BuildRow exhausted = kernel.recordFailure(
                exhaustedFixture.scope(),
                exhaustedClaim,
                "exhausted-owner",
                new KnowledgeIndexBuildFailure(
                        "APVERO_TEST_TRANSIENT",
                        KnowledgeIndexBuildFailure.Category.TRANSIENT,
                        true,
                        false));
        assertThat(exhausted.status()).isEqualTo(BuildStatus.FAILED);
        assertThat(exhausted.retryable()).isTrue();

        WorkspaceFixture ambiguousFixture = createWorkspace("ambiguous");
        insertBuild(ambiguousFixture, 1, 3, OffsetDateTime.now(ZoneOffset.UTC));
        BuildRow ambiguousClaim =
                kernel.claim(ambiguousFixture.scope(), "ambiguous-owner", 1).getFirst();
        BuildRow ambiguous = kernel.recordFailure(
                ambiguousFixture.scope(),
                ambiguousClaim,
                "ambiguous-owner",
                new KnowledgeIndexBuildFailure(
                        "APVERO_TEST_AMBIGUOUS",
                        KnowledgeIndexBuildFailure.Category.AMBIGUOUS,
                        false,
                        true));
        assertThat(ambiguous.status()).isEqualTo(BuildStatus.FAILED);
        assertThat(ambiguous.retryable()).isFalse();
        assertThat(ambiguous.reconciliationRequired()).isTrue();
        assertThat(ambiguous.failureMetadataJson()).isEqualTo("{}");
    }

    @Test
    void wrongScopeOwnerVersionAndInvalidProgressFailClosed() {
        WorkspaceFixture owner = createWorkspace("fail-closed-owner");
        WorkspaceFixture outsider = createWorkspace("fail-closed-outsider");
        insertBuild(owner, 2, 3, OffsetDateTime.now(ZoneOffset.UTC));
        BuildRow claim = kernel.claim(owner.scope(), "right-owner", 1).getFirst();

        assertThatThrownBy(() -> kernel.renew(outsider.scope(), claim, "right-owner"))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT");
        assertThatThrownBy(() -> kernel.renew(owner.scope(), claim, "wrong-owner"))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT");
        assertThatThrownBy(() -> kernel.recordEmbeddingProgressAndRelease(
                        owner.scope(), claim, "right-owner", 3, 2))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_PROGRESS_INVALID");

        BuildRow renewed = kernel.renew(owner.scope(), claim, "right-owner");
        assertThatThrownBy(() -> kernel.renew(owner.scope(), claim, "right-owner"))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT");
        assertThat(renewed.lockVersion()).isEqualTo(claim.lockVersion() + 1);
    }

    @Test
    void waitingCancellationAndClaimCannotBothWin() {
        WorkspaceFixture cancelFirstFixture = createWorkspace("cancel-first");
        UUID cancelledId = insertBuild(
                cancelFirstFixture, 1, 3, OffsetDateTime.now(ZoneOffset.UTC));
        BuildRow waiting = repository.findBuild(cancelFirstFixture.scope(), cancelledId).orElseThrow();
        assertThat(repository.cancelWaitingBuild(
                        cancelFirstFixture.scope(),
                        cancelledId,
                        waiting.lockVersion(),
                        OffsetDateTime.now(ZoneOffset.UTC)))
                .isPresent();
        assertThat(kernel.claim(cancelFirstFixture.scope(), "after-cancel", 1)).isEmpty();

        WorkspaceFixture claimFirstFixture = createWorkspace("claim-first");
        UUID claimedId = insertBuild(
                claimFirstFixture, 1, 3, OffsetDateTime.now(ZoneOffset.UTC));
        BuildRow claimed = kernel.claim(claimFirstFixture.scope(), "claim-winner", 1).getFirst();
        assertThat(repository.cancelWaitingBuild(
                        claimFirstFixture.scope(),
                        claimedId,
                        claimed.lockVersion(),
                        OffsetDateTime.now(ZoneOffset.UTC)))
                .isEmpty();
        assertThat(repository.findBuild(claimFirstFixture.scope(), claimedId).orElseThrow().status())
                .isEqualTo(BuildStatus.EMBEDDING);
    }

    @Test
    void rollbackRestoresProgressFailureAndForwardTransitions() {
        WorkspaceFixture fixture = createWorkspace("mutation-rollback");
        UUID buildId = insertBuild(fixture, 2, 3, OffsetDateTime.now(ZoneOffset.UTC));
        BuildRow progressClaim = kernel.claim(fixture.scope(), "rollback-progress", 1).getFirst();
        transactions.executeWithoutResult(status -> {
            kernel.recordEmbeddingProgressAndRelease(
                    fixture.scope(), progressClaim, "rollback-progress", 1, 0);
            status.setRollbackOnly();
        });
        assertThat(repository.findBuild(fixture.scope(), buildId).orElseThrow())
                .isEqualTo(progressClaim);

        BuildRow completed = kernel.recordEmbeddingProgressAndRelease(
                fixture.scope(), progressClaim, "rollback-progress", 2, 1);
        BuildRow indexingClaim =
                kernel.claim(fixture.scope(), "rollback-indexing", 1).getFirst();
        transactions.executeWithoutResult(status -> {
            kernel.advanceToIndexingAndRelease(
                    fixture.scope(), indexingClaim, "rollback-indexing");
            status.setRollbackOnly();
        });
        assertThat(repository.findBuild(fixture.scope(), buildId).orElseThrow())
                .isEqualTo(indexingClaim);

        BuildRow indexing = kernel.advanceToIndexingAndRelease(
                fixture.scope(), indexingClaim, "rollback-indexing");
        assertThat(indexing.lockVersion()).isGreaterThan(completed.lockVersion());
        BuildRow validatingClaim =
                kernel.claim(fixture.scope(), "rollback-validating", 1).getFirst();
        transactions.executeWithoutResult(status -> {
            kernel.advanceToValidatingAndRelease(
                    fixture.scope(), validatingClaim, "rollback-validating", 2, digest('8'));
            status.setRollbackOnly();
        });
        assertThat(repository.findBuild(fixture.scope(), buildId).orElseThrow())
                .isEqualTo(validatingClaim);

        WorkspaceFixture failureFixture = createWorkspace("failure-rollback");
        UUID failedBuild = insertBuild(
                failureFixture, 1, 3, OffsetDateTime.now(ZoneOffset.UTC));
        BuildRow failureClaim =
                kernel.claim(failureFixture.scope(), "rollback-failure", 1).getFirst();
        transactions.executeWithoutResult(status -> {
            kernel.recordFailure(
                    failureFixture.scope(),
                    failureClaim,
                    "rollback-failure",
                    new KnowledgeIndexBuildFailure(
                            "APVERO_TEST_ROLLBACK",
                            KnowledgeIndexBuildFailure.Category.TRANSIENT,
                            true,
                            false));
            status.setRollbackOnly();
        });
        assertThat(repository.findBuild(failureFixture.scope(), failedBuild).orElseThrow())
                .isEqualTo(failureClaim);
    }

    private WorkspaceFixture createWorkspace(String label) {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID indexId = UUID.randomUUID();
        String suffix = workspaceId.toString().replace("-", "").substring(0, 12);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        sql.update(
                "insert into tenant(id, slug, name, created_at) values (?, ?, ?, ?)",
                tenantId,
                "t-" + suffix,
                "Tenant " + label,
                now);
        sql.update("""
                insert into workspace(id, tenant_id, slug, name, created_at)
                values (?, ?, ?, ?, ?)
                """, workspaceId, tenantId, "w-" + suffix, "Workspace " + label, now);
        sql.update("""
                insert into knowledge_base(
                    id, tenant_id, workspace_id, slug, name, description, status,
                    version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '', 'ACTIVE', 1, ?, ?)
                """, baseId, tenantId, workspaceId, "base-" + suffix, "Base " + label, now, now);
        sql.update("""
                insert into model_provider(
                    id, tenant_id, workspace_id, name, provider_type, base_url,
                    enabled, version, created_at, updated_at)
                values (?, ?, ?, ?, 'DETERMINISTIC_LOCAL', 'local://deterministic',
                    true, 1, ?, ?)
                """, providerId, tenantId, workspaceId, "Provider " + label, now, now);
        sql.update("""
                insert into model_definition(
                    id, tenant_id, workspace_id, provider_id, model_key, name,
                    capabilities, input_cost_micros_per_million,
                    output_cost_micros_per_million, enabled, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, '["EMBEDDING"]'::jsonb, 0, 0, true, ?, ?)
                """, modelId, tenantId, workspaceId, providerId,
                "model-" + suffix, "Model " + label, now, now);
        sql.update("""
                insert into model_route(
                    id, tenant_id, workspace_id, name, version, model_id, status,
                    timeout_ms, route_capability, embedding_dimension,
                    embedding_maximum_input_tokens, embedding_maximum_batch_size,
                    embedding_normalization, created_at)
                values (?, ?, ?, ?, 1, ?, 'PUBLISHED', 30000, 'EMBEDDING',
                    3, 8192, 64, 'NONE', ?)
                """, routeId, tenantId, workspaceId, "route-" + suffix, modelId, now);
        sql.update("""
                insert into knowledge_index(
                    id, tenant_id, workspace_id, knowledge_base_id, slug, name, status,
                    metadata_version, version_count, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE', 1, 0, ?, ?)
                """, indexId, tenantId, workspaceId, baseId,
                "index-" + suffix, "Index " + label, now, now);
        return new WorkspaceFixture(
                new WorkspaceScope(tenantId, workspaceId),
                baseId,
                routeId,
                "route-" + suffix + "@1",
                indexId);
    }

    private UUID insertBuild(
            WorkspaceFixture fixture,
            int chunkCount,
            int maximumAttempts,
            OffsetDateTime createdAt) {
        UUID buildId = UUID.randomUUID();
        String version = "1.0." + Math.floorMod(buildId.hashCode(), 100000);
        sql.update("""
                insert into knowledge_index_build(
                    id, tenant_id, workspace_id, knowledge_index_id, knowledge_base_id,
                    requested_version, embedding_route_id, embedding_route_reference,
                    vector_dimension, maximum_input_tokens, maximum_batch_size, normalization,
                    request_digest, source_set_digest, requested_source_count, requested_chunk_count,
                    status, current_step, attempt_count, maximum_attempts, retryable,
                    lock_version, cancellation_requested, embedded_entry_count,
                    validated_entry_count, reconciliation_required, failure_metadata,
                    created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 3, 8192, 64, 'NONE',
                    ?, ?, 1, ?, 'QUEUED', 'EMBEDDING', 0, ?, false,
                    1, false, 0, 0, false, '{}'::jsonb, ?, ?)
                """,
                buildId,
                fixture.scope().tenantId(),
                fixture.scope().workspaceId(),
                fixture.indexId(),
                fixture.baseId(),
                version,
                fixture.routeId(),
                fixture.routeReference(),
                digest("request-" + buildId),
                digest("source-" + buildId),
                chunkCount,
                maximumAttempts,
                createdAt,
                createdAt);
        return buildId;
    }

    private void expireLeaseAtDatabaseBoundary(UUID buildId) {
        sql.update("""
                update knowledge_index_build
                set lease_until = transaction_timestamp(),
                    lock_version = lock_version + 1,
                    updated_at = transaction_timestamp()
                where id = ?
                """, buildId);
    }

    private void makeRetryDue(UUID buildId) {
        sql.update("""
                update knowledge_index_build
                set next_attempt_at = transaction_timestamp() - interval '1 millisecond',
                    lock_version = lock_version + 1,
                    updated_at = transaction_timestamp()
                where id = ? and status = 'RETRY_WAIT'
                """, buildId);
    }

    private static String digest(char value) {
        return digest(String.valueOf(value));
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

    private record WorkspaceFixture(
            WorkspaceScope scope,
            UUID baseId,
            UUID routeId,
            String routeReference,
            UUID indexId) {}
}
