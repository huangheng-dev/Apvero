package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingExecutionResult;
import io.apvero.platform.governance.ExecutionComponentState;
import io.apvero.platform.governance.ExecutionGovernance;
import io.apvero.platform.governance.ExecutionUsageQuality;
import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingLeaseCoordinator.AdmittedComponent;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildSourceCandidateRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPublicationCheckpoint.Stage;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPublicationOutcome.Status;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    @Autowired KnowledgeIndexBuildValidationOrchestrator validation;
    @Autowired KnowledgeIndexPublicationCoordinator publication;
    @MockitoBean KnowledgeIndexPublicationCheckpoint publicationCheckpoint;
    @Autowired KnowledgeEmbeddingBatchExecutor batches;
    @Autowired KnowledgeEmbeddingLeaseCoordinator coordinator;
    @Autowired EmbeddingCapability embeddings;
    @Autowired KnowledgeIndexPersistenceRepository repository;
    @Autowired ExecutionGovernance governance;
    @Autowired AuditEventCatalog auditEvents;
    @Autowired JdbcTemplate sql;

    @ParameterizedTest(name = "publishes the reference envelope at {0} entries")
    @ValueSource(ints = {1, 100, 1000})
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void validatesAndPublishesTheReferenceEntryEnvelope(int entryCount) {
        Fixture fixture = createFixture(entryCount);
        BuildRow build = repository.findBuild(fixture.scope(), fixture.buildId()).orElseThrow();
        long queueStarted = System.nanoTime();

        while (build.currentStep() == BuildStep.EMBEDDING) {
            BuildRow claim = kernel.claim(
                    fixture.scope(), "d5-envelope-worker", 1).getFirst();
            build = orchestrator.executeClaim(
                    fixture.scope(), claim, "d5-envelope-worker").build();
        }
        long validationStarted = System.nanoTime();
        BuildRow validationClaim =
                kernel.claim(fixture.scope(), "d5-envelope-worker", 1).getFirst();
        BuildRow validated = validation.executeClaim(
                fixture.scope(), validationClaim, "d5-envelope-worker").build();
        BuildRow publicationClaim =
                kernel.claim(fixture.scope(), "d5-envelope-worker", 1).getFirst();
        long publicationStarted = System.nanoTime();
        KnowledgeIndexPublicationOutcome published = publication.publish(
                fixture.scope(), publicationClaim, "d5-envelope-worker");
        long completed = System.nanoTime();

        assertThat(validated.status()).isEqualTo(BuildStatus.VALIDATING);
        assertThat(published.status()).isEqualTo(Status.PUBLISHED);
        assertThat(published.build().status()).isEqualTo(BuildStatus.READY);
        assertThat(repository.listEntries(fixture.scope(), fixture.buildId()))
                .hasSize(entryCount);
        assertThat(repository.listVersions(
                fixture.scope(), published.index().id())).hasSize(1);
        System.out.printf(
                "P2.2d-5 envelope entries=%d queueToValidationMs=%d "
                        + "validationMs=%d publicationMs=%d%n",
                entryCount,
                TimeUnit.NANOSECONDS.toMillis(validationStarted - queueStarted),
                TimeUnit.NANOSECONDS.toMillis(publicationStarted - validationStarted),
                TimeUnit.NANOSECONDS.toMillis(completed - publicationStarted));
    }

    @Test
    void closesGovernedEmbeddingAndAdvancesCompleteArtifactToValidating() {
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

        BuildRow validationClaim =
                kernel.claim(fixture.scope(), "d4-validation-worker", 1).getFirst();
        KnowledgeIndexValidationClaimOutcome validated =
                validation.executeClaim(
                        fixture.scope(), validationClaim, "d4-validation-worker");

        assertThat(validated.status())
                .isEqualTo(KnowledgeIndexValidationClaimOutcome.Status.ADVANCED_TO_VALIDATING);
        assertThat(validated.build().status()).isEqualTo(BuildStatus.VALIDATING);
        assertThat(validated.build().currentStep()).isEqualTo(BuildStep.VALIDATING);
        assertThat(validated.build().validatedEntryCount()).isEqualTo(1);
        assertThat(validated.build().validationDigest())
                .matches("^sha256:[a-f0-9]{64}$");
        assertThat(validated.build().artifactDigest()).isNull();
        assertThat(validated.build().leaseOwner()).isNull();
        assertThat(validated.build().lockVersion())
                .isEqualTo(validationClaim.lockVersion() + 1);

        BuildRow publicationClaim =
                kernel.claim(fixture.scope(), "d4-publication-worker", 1).getFirst();
        KnowledgeIndexPublicationOutcome published = publication.publish(
                fixture.scope(), publicationClaim, "d4-publication-worker");

        assertThat(published.build().status()).isEqualTo(BuildStatus.READY);
        assertThat(published.status()).isEqualTo(Status.PUBLISHED);
        assertThat(published.build().currentStep()).isEqualTo(BuildStep.COMPLETE);
        assertThat(published.build().artifactDigest())
                .isEqualTo(published.version().artifactDigest());
        assertThat(published.build().publishedVersionId())
                .isEqualTo(published.version().id());
        assertThat(published.build().lockVersion())
                .isEqualTo(publicationClaim.lockVersion() + 2);
        assertThat(published.version().id()).isEqualTo(
                KnowledgeCanonicalDigests.stableId(
                        "apvero:knowledge-index-version:" + fixture.buildId()));
        assertThat(published.version().reference()).startsWith("index-");
        assertThat(published.version().reference()).endsWith("@1.0.0");
        assertThat(published.version().publishedAt()).isNotNull();
        assertThat(published.index().versionCount()).isEqualTo(1);
        assertThat(published.index().metadataVersion()).isEqualTo(2);
        assertThat(published.index().latestReadyVersionId())
                .isEqualTo(published.version().id());
        assertThat(auditEvents.listAuditEvents(fixture.scope().workspaceId()))
                .anySatisfy(event -> {
                    assertThat(event.action())
                            .isEqualTo("knowledge.index-version.published");
                    assertThat(event.resourceType())
                            .isEqualTo("knowledge-index-version");
                    assertThat(event.resourceId())
                            .isEqualTo(published.version().id().toString());
                    assertThat(event.outcome()).isEqualTo("SUCCEEDED");
                });

        int auditCount = auditEvents.listAuditEvents(
                fixture.scope().workspaceId()).size();
        KnowledgeIndexPublicationOutcome replayed = publication.publish(
                fixture.scope(), publicationClaim, "d4-publication-worker");

        assertThat(replayed.status()).isEqualTo(Status.REPLAYED);
        assertThat(replayed.version()).isEqualTo(published.version());
        assertThat(replayed.build()).isEqualTo(published.build());
        assertThat(repository.listVersions(
                fixture.scope(), published.index().id())).hasSize(1);
        assertThat(auditEvents.listAuditEvents(
                fixture.scope().workspaceId())).hasSize(auditCount);

        assertThatThrownBy(() -> publication.publish(
                        fixture.scope(),
                        withRequestDigest(publicationClaim, digest("conflict")),
                        "d4-publication-worker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_KNOWLEDGE_PUBLICATION_CONFLICT");
        assertThat(repository.listVersions(
                fixture.scope(), published.index().id())).hasSize(1);
        assertThat(auditEvents.listAuditEvents(
                fixture.scope().workspaceId())).hasSize(auditCount);
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void rollsBackEveryPublicationMutationBoundary(Stage failureStage) {
        Fixture fixture = createFixture();
        BuildRow publicationClaim = preparePublicationClaim(
                fixture, "d4-boundary-worker");
        BuildRow before = repository.findBuild(
                fixture.scope(), fixture.buildId()).orElseThrow();
        var indexBefore = repository.findIndex(
                fixture.scope(), before.knowledgeIndexId()).orElseThrow();
        doAnswer(invocation -> {
            if (invocation.getArgument(0) == failureStage) {
                throw new IllegalStateException(
                        "APVERO_KNOWLEDGE_PUBLICATION_TEST_ROLLBACK");
            }
            return null;
        }).when(publicationCheckpoint).after(any(Stage.class));

        assertThatThrownBy(() -> publication.publish(
                        fixture.scope(), publicationClaim, "d4-boundary-worker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_KNOWLEDGE_PUBLICATION_TEST_ROLLBACK");

        assertPublicationAbsent(fixture, before, indexBefore);
        assertThat(auditEvents.listAuditEvents(
                fixture.scope().workspaceId()))
                .noneMatch(event -> event.action().equals(
                        "knowledge.index-version.published"));
    }

    @Test
    void serializesTwoPublishersIntoOnePublicationAndOneEqualReplay()
            throws Exception {
        Fixture fixture = createFixture();
        BuildRow claim = preparePublicationClaim(
                fixture, "d4-concurrent-worker");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<KnowledgeIndexPublicationOutcome> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return publication.publish(
                        fixture.scope(), claim, "d4-concurrent-worker");
            });
            Future<KnowledgeIndexPublicationOutcome> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return publication.publish(
                        fixture.scope(), claim, "d4-concurrent-worker");
            });
            ready.await();
            start.countDown();

            assertThat(List.of(first.get().status(), second.get().status()))
                    .containsExactlyInAnyOrder(Status.PUBLISHED, Status.REPLAYED);
        }

        BuildRow build = repository.findBuild(
                fixture.scope(), fixture.buildId()).orElseThrow();
        assertThat(repository.listVersions(
                fixture.scope(), build.knowledgeIndexId())).hasSize(1);
        assertThat(auditEvents.listAuditEvents(
                fixture.scope().workspaceId()))
                .filteredOn(event -> event.action().equals(
                        "knowledge.index-version.published"))
                .hasSize(1);
    }

    @Test
    void publishesTwoIndependentIndexesConcurrentlyWithoutLockInversion()
            throws Exception {
        Fixture firstFixture = createFixture();
        Fixture secondFixture = createFixture();
        BuildRow firstClaim = preparePublicationClaim(
                firstFixture, "d4-index-race-first");
        BuildRow secondClaim = preparePublicationClaim(
                secondFixture, "d4-index-race-second");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<KnowledgeIndexPublicationOutcome> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return publication.publish(
                        firstFixture.scope(),
                        firstClaim,
                        "d4-index-race-first");
            });
            Future<KnowledgeIndexPublicationOutcome> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return publication.publish(
                        secondFixture.scope(),
                        secondClaim,
                        "d4-index-race-second");
            });
            ready.await();
            start.countDown();

            assertThat(first.get().status()).isEqualTo(Status.PUBLISHED);
            assertThat(second.get().status()).isEqualTo(Status.PUBLISHED);
        }
        assertThat(repository.listVersions(
                firstFixture.scope(), firstClaim.knowledgeIndexId())).hasSize(1);
        assertThat(repository.listVersions(
                secondFixture.scope(), secondClaim.knowledgeIndexId())).hasSize(1);
    }

    @Test
    void rejectsArchivedIndexAndForeignWorkspaceWithoutPublication() {
        Fixture archived = createFixture();
        BuildRow archivedClaim = preparePublicationClaim(
                archived, "d4-archived-worker");
        BuildRow archivedBefore = repository.findBuild(
                archived.scope(), archived.buildId()).orElseThrow();
        var archivedIndexBefore = repository.findIndex(
                archived.scope(), archivedBefore.knowledgeIndexId()).orElseThrow();
        sql.update("""
                update knowledge_index
                set status = 'ARCHIVED',
                    metadata_version = metadata_version + 1,
                    updated_at = transaction_timestamp()
                where id = ? and tenant_id = ? and workspace_id = ?
                """, archivedBefore.knowledgeIndexId(),
                archived.scope().tenantId(), archived.scope().workspaceId());

        assertThatThrownBy(() -> publication.publish(
                        archived.scope(),
                        archivedClaim,
                        "d4-archived-worker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_PUBLICATION_ARCHIVED");
        assertThat(repository.listVersions(
                archived.scope(), archivedBefore.knowledgeIndexId())).isEmpty();
        assertThat(repository.findBuild(
                archived.scope(), archived.buildId()).orElseThrow()
                .artifactDigest()).isNull();

        Fixture scoped = createFixture();
        BuildRow scopedClaim = preparePublicationClaim(
                scoped, "d4-scope-worker");
        WorkspaceScope foreign = new WorkspaceScope(
                UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> publication.publish(
                        foreign, scopedClaim, "d4-scope-worker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_NOT_FOUND");
        BuildRow scopedAfter = repository.findBuild(
                scoped.scope(), scoped.buildId()).orElseThrow();
        assertThat(scopedAfter.artifactDigest()).isNull();
        assertThat(repository.listVersions(
                scoped.scope(), scopedAfter.knowledgeIndexId())).isEmpty();
        assertThat(archivedIndexBefore.versionCount()).isZero();
    }

    @Test
    void rollsBackVersionBuildIndexAndArtifactWhenAuditAppendFails() {
        Fixture fixture = createFixture();
        BuildRow publicationClaim = preparePublicationClaim(
                fixture, "d4-audit-rollback-worker");
        BuildRow before = repository.findBuild(
                fixture.scope(), fixture.buildId()).orElseThrow();
        var indexBefore = repository.findIndex(
                fixture.scope(), before.knowledgeIndexId()).orElseThrow();

        sql.execute("""
                create or replace function reject_d4_publication_audit()
                returns trigger language plpgsql as $$
                begin
                    if new.action = 'knowledge.index-version.published' then
                        raise exception 'injected publication audit failure';
                    end if;
                    return new;
                end;
                $$
                """);
        sql.execute("""
                create trigger reject_d4_publication_audit_trigger
                before insert on audit_event
                for each row execute function reject_d4_publication_audit()
                """);
        try {
            assertThatThrownBy(() -> publication.publish(
                            fixture.scope(),
                            publicationClaim,
                            "d4-audit-rollback-worker"))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            sql.execute("""
                    drop trigger if exists reject_d4_publication_audit_trigger
                    on audit_event
                    """);
            sql.execute("drop function if exists reject_d4_publication_audit()");
        }

        BuildRow after = repository.findBuild(
                fixture.scope(), fixture.buildId()).orElseThrow();
        var indexAfter = repository.findIndex(
                fixture.scope(), before.knowledgeIndexId()).orElseThrow();
        assertThat(after.status()).isEqualTo(BuildStatus.VALIDATING);
        assertThat(after.currentStep()).isEqualTo(BuildStep.VALIDATING);
        assertThat(after.artifactDigest()).isNull();
        assertThat(after.publishedVersionId()).isNull();
        assertThat(after.lockVersion()).isEqualTo(before.lockVersion());
        assertThat(repository.listVersions(
                fixture.scope(), before.knowledgeIndexId())).isEmpty();
        assertThat(indexAfter.metadataVersion())
                .isEqualTo(indexBefore.metadataVersion());
        assertThat(indexAfter.versionCount()).isEqualTo(indexBefore.versionCount());
        assertThat(indexAfter.latestReadyVersionId())
                .isEqualTo(indexBefore.latestReadyVersionId());
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

    private BuildRow preparePublicationClaim(Fixture fixture, String worker) {
        BuildRow embeddingClaim =
                kernel.claim(fixture.scope(), worker, 1).getFirst();
        orchestrator.executeClaim(fixture.scope(), embeddingClaim, worker);
        BuildRow indexingClaim =
                kernel.claim(fixture.scope(), worker, 1).getFirst();
        orchestrator.executeClaim(fixture.scope(), indexingClaim, worker);
        BuildRow validationClaim =
                kernel.claim(fixture.scope(), worker, 1).getFirst();
        validation.executeClaim(fixture.scope(), validationClaim, worker);
        return kernel.claim(fixture.scope(), worker, 1).getFirst();
    }

    private void assertPublicationAbsent(
            Fixture fixture,
            BuildRow buildBefore,
            KnowledgeIndexPersistenceRecords.IndexRow indexBefore) {
        BuildRow buildAfter = repository.findBuild(
                fixture.scope(), fixture.buildId()).orElseThrow();
        var indexAfter = repository.findIndex(
                fixture.scope(), buildBefore.knowledgeIndexId()).orElseThrow();
        assertThat(buildAfter.status()).isEqualTo(BuildStatus.VALIDATING);
        assertThat(buildAfter.currentStep()).isEqualTo(BuildStep.VALIDATING);
        assertThat(buildAfter.artifactDigest()).isNull();
        assertThat(buildAfter.publishedVersionId()).isNull();
        assertThat(buildAfter.lockVersion()).isEqualTo(buildBefore.lockVersion());
        assertThat(repository.listVersions(
                fixture.scope(), buildBefore.knowledgeIndexId())).isEmpty();
        assertThat(indexAfter.metadataVersion())
                .isEqualTo(indexBefore.metadataVersion());
        assertThat(indexAfter.versionCount()).isEqualTo(indexBefore.versionCount());
        assertThat(indexAfter.latestReadyVersionId())
                .isEqualTo(indexBefore.latestReadyVersionId());
    }

    private static BuildRow withRequestDigest(BuildRow row, String requestDigest) {
        return new BuildRow(
                row.id(),
                row.tenantId(),
                row.workspaceId(),
                row.knowledgeIndexId(),
                row.knowledgeBaseId(),
                row.requestedVersion(),
                row.embeddingRouteId(),
                row.embeddingRouteReference(),
                row.vectorDimension(),
                row.maximumInputTokens(),
                row.maximumBatchSize(),
                row.normalization(),
                requestDigest,
                row.sourceSetDigest(),
                row.requestedSourceCount(),
                row.requestedChunkCount(),
                row.status(),
                row.currentStep(),
                row.attemptCount(),
                row.maximumAttempts(),
                row.retryable(),
                row.nextAttemptAt(),
                row.leaseOwner(),
                row.leaseUntil(),
                row.lockVersion(),
                row.cancellationRequested(),
                row.embeddedEntryCount(),
                row.validatedEntryCount(),
                row.lastDurableChunkOrdinal(),
                row.validationDigest(),
                row.artifactDigest(),
                row.publishedVersionId(),
                row.errorCode(),
                row.errorCategory(),
                row.reconciliationRequired(),
                row.failureMetadataJson(),
                row.startedAt(),
                row.completedAt(),
                row.createdAt(),
                row.updatedAt());
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
        return createFixture(1);
    }

    private Fixture createFixture(int entryCount) {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        WorkspaceScope scope = new WorkspaceScope(tenantId, workspaceId);
        UUID baseId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        List<UUID> chunkIds = IntStream.range(0, entryCount)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        UUID chunkId = chunkIds.getFirst();
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
        List<Object[]> chunks = IntStream.range(0, entryCount)
                .mapToObj(ordinal -> {
                    String text = "hello-" + ordinal;
                    return new Object[] {
                        chunkIds.get(ordinal),
                        tenantId,
                        workspaceId,
                        revisionId,
                        documentId,
                        ordinal,
                        text,
                        digest(text),
                        ordinal * 16,
                        ordinal * 16 + text.length(),
                        ordinal + 1,
                        ordinal + 1,
                        ordinal + 1,
                        now
                    };
                })
                .toList();
        sql.batchUpdate("""
                insert into knowledge_chunk(
                    id, tenant_id, workspace_id, source_revision_id, document_id,
                    ordinal, text, content_digest, start_offset, end_offset,
                    paragraph_number, line_start, line_end, chunker_version, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'apvero-boundary@1.0.0', ?)
                """, chunks);
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

        String sourceSetDigest = KnowledgeIndexBuildDigests.sourceSet(List.of(
                new BuildSourceCandidateRow(
                        sourceId,
                        revisionId,
                        digest("source"),
                        "apvero-text@1.0.0",
                        "apvero-boundary@1.0.0",
                        1,
                        entryCount)));
        BuildRow build = repository.insertBuild(scope, new BuildRow(
                buildId, tenantId, workspaceId, indexId, baseId, "1.0.0",
                routeId, routeName + "@1", 256, 8192, 64, "L2",
                digest("request-" + entryCount), sourceSetDigest, 1, entryCount,
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
