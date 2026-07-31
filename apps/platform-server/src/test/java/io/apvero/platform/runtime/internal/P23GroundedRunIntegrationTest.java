package io.apvero.platform.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.application.BindApplicationDraftCommand;
import io.apvero.platform.application.CreateApplicationCommand;
import io.apvero.platform.application.ReplaceApplicationKnowledgeBindingsCommand;
import io.apvero.platform.application.RuntimeMode;
import io.apvero.platform.knowledge.KnowledgeIndexVersion;
import io.apvero.platform.knowledge.KnowledgeIndexVersionCatalog;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeDisabledException;
import io.apvero.platform.knowledge.KnowledgeRetrievalHit;
import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import io.apvero.platform.knowledge.KnowledgeRuntimeRetrieval;
import io.apvero.platform.knowledge.KnowledgeRuntimeRetrievalResult;
import io.apvero.platform.knowledge.KnowledgeSource;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.RetrievalPolicyOverlapHandling;
import io.apvero.platform.knowledge.RetrievalPolicyVersion;
import io.apvero.platform.knowledge.RetrievalPolicyVersionCatalog;
import io.apvero.platform.governance.RetentionPolicyCatalog;
import io.apvero.platform.release.CreateReleaseCommand;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleaseCatalog;
import io.apvero.platform.runtime.ExecuteRunCommand;
import io.apvero.platform.runtime.ProviderExecutionException;
import io.apvero.platform.runtime.ProviderFailureDisposition;
import io.apvero.platform.runtime.ProviderRequest;
import io.apvero.platform.runtime.ProviderResult;
import io.apvero.platform.runtime.RunCatalog;
import io.apvero.platform.runtime.RunCitationCatalog;
import io.apvero.platform.runtime.RunEvidenceException;
import io.apvero.platform.runtime.RunStatus;
import io.apvero.platform.runtime.RuntimeProvider;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import(P23GroundedRunIntegrationTest.RuntimeStubConfiguration.class)
class P23GroundedRunIntegrationTest {
    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ROUTE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000003201");
    private static final UUID PROMPT_VERSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000004101");
    private static final UUID LEGACY_APPLICATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000001001");
    private static final UUID LEGACY_RELEASE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000002001");
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p23_test")
            .withUsername("apvero")
            .withPassword("apvero");

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

    @Autowired ApplicationCatalog applications;
    @Autowired ReleaseCatalog releases;
    @Autowired RunCatalog runs;
    @Autowired RunCitationCatalog citations;
    @Autowired JdbcTemplate sql;
    @Autowired ObjectMapper json;
    @Autowired RetentionPolicyCatalog retentionPolicies;
    @MockitoBean KnowledgeIndexVersionCatalog indexes;
    @MockitoBean RetrievalPolicyVersionCatalog policies;
    @MockitoBean RuntimeProviderRegistry providers;
    @MockitoSpyBean RunLifecycle lifecycle;
    @Autowired
    @Qualifier("p23KnowledgeRuntime")
    KnowledgeRuntimeRetrieval retrieval;

    @BeforeEach
    void resetMocks() {
        reset(indexes, policies, retrieval, providers);
        retentionPolicies.update(WORKSPACE_ID, 30, 365, true, false);
    }

    @Test
    void executesLegacyManifestOneAndExplicitManifestElevenChatWithoutRagFallback() {
        when(providers.resolve(any()))
                .thenReturn(new DeterministicLocalProvider(json));
        var legacy = runs.execute(
                WORKSPACE_ID,
                LEGACY_APPLICATION_ID,
                new ExecuteRunCommand(
                        LEGACY_RELEASE_ID,
                        json.createObjectNode().put("message", "Legacy CHAT"),
                        "p23f-test"));

        UUID explicitReleaseId = UUID.randomUUID();
        String explicitManifest = """
                {
                  "schemaVersion":"1.1",
                  "runtimeMode":"CHAT",
                  "modelRouteVersion":"local-deterministic@1",
                  "promptVersion":"prompt@1",
                  "outputSchemaVersion":"output@1",
                  "knowledgeBindings":[],
                  "capabilityVersions":[],
                  "policyVersions":["baseline@1"],
                  "memoryPolicyVersion":"session@1",
                  "evaluationReportVersion":"not-evaluated@1",
                  "runtimeParameters":{"temperature":0,"maxOutputTokens":512}
                }
                """;
        sql.update(
                """
                insert into release_bundle(
                    id, tenant_id, workspace_id, application_id, version,
                    artifact_digest, manifest, status, purpose, created_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, 'RELEASED', 'PRODUCTION', now())
                """,
                explicitReleaseId,
                TENANT_ID,
                WORKSPACE_ID,
                LEGACY_APPLICATION_ID,
                "2.3.0-chat-" + explicitReleaseId,
                "f".repeat(64),
                explicitManifest);
        var explicit = runs.execute(
                WORKSPACE_ID,
                LEGACY_APPLICATION_ID,
                new ExecuteRunCommand(
                        explicitReleaseId,
                        json.createObjectNode().put("message", "Explicit CHAT"),
                        "p23f-test"));

        assertThat(legacy.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(legacy.output().path("message").stringValue())
                .isEqualTo("Apvero received: Legacy CHAT");
        assertThat(explicit.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(explicit.output().path("message").stringValue())
                .isEqualTo("Apvero received: Explicit CHAT");
        assertThat(sql.queryForObject(
                        """
                        select manifest ->> 'modelRouteVersion'
                        from release_bundle where id = ?
                        """,
                        String.class,
                        LEGACY_RELEASE_ID))
                .isEqualTo("local-deterministic@1.0.0");
        assertThat(sql.queryForObject(
                        """
                        select count(*) from ai_run_retrieval
                        where run_id in (?, ?)
                        """,
                        Integer.class,
                        legacy.id(),
                        explicit.id()))
                .isZero();
    }

    @Test
    void completesTypedNoEvidenceWithoutChatReservationOrProviderCall() {
        Fixture fixture = release("no-evidence");
        when(retrieval.retrieveForRun(
                        eq(WORKSPACE_ID),
                        any(),
                        eq(fixture.index().id()),
                        eq(fixture.policy().id()),
                        eq("Where is the policy?")))
                .thenReturn(new KnowledgeRuntimeRetrievalResult(
                        new KnowledgeRetrievalResult(
                                KnowledgeRetrievalResult.Status.NO_EVIDENCE,
                                fixture.index().id(),
                                fixture.policy().id(),
                                digest('a'),
                                List.of(),
                                3),
                        1,
                        true,
                        false));
        var input = json.createObjectNode().put("message", "Where is the policy?");

        var run = runs.execute(
                WORKSPACE_ID,
                fixture.release().applicationId(),
                new ExecuteRunCommand(
                        fixture.release().id(), input, "p23d-test"));

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.providerId()).isEqualTo("none");
        assertThat(run.governanceReservationId()).isNull();
        assertThat(run.output().path("status").stringValue()).isEqualTo("NO_EVIDENCE");
        assertThat(sql.queryForObject(
                        "select count(*) from execution_reservation where trace_id = ?",
                        Integer.class,
                        run.traceId()))
                .isZero();
        assertThat(sql.queryForObject(
                        "select status from ai_run_retrieval where run_id = ?",
                        String.class,
                        run.id()))
                .isEqualTo("NO_EVIDENCE");
        verify(providers, never()).resolve(fixture.release());
    }

    @Test
    void preservesTypedNoEvidenceWhileMaskingSensitiveInputFields() {
        retentionPolicies.update(WORKSPACE_ID, 30, 365, true, true);
        Fixture fixture = release("masked-no-evidence");
        when(retrieval.retrieveForRun(
                        eq(WORKSPACE_ID),
                        any(),
                        eq(fixture.index().id()),
                        eq(fixture.policy().id()),
                        eq("Where is the policy?")))
                .thenReturn(new KnowledgeRuntimeRetrievalResult(
                        new KnowledgeRetrievalResult(
                                KnowledgeRetrievalResult.Status.NO_EVIDENCE,
                                fixture.index().id(),
                                fixture.policy().id(),
                                digest('9'),
                                List.of(),
                                3),
                        retentionPolicies.getOrCreate(WORKSPACE_ID).version(),
                        true,
                        true));
        var input = json.createObjectNode()
                .put("message", "Where is the policy?")
                .put("apiToken", "must-not-be-retained");

        var run = runs.execute(
                WORKSPACE_ID,
                fixture.release().applicationId(),
                new ExecuteRunCommand(
                        fixture.release().id(), input, "p23d-test"));

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.input().path("message").stringValue())
                .isEqualTo("Where is the policy?");
        assertThat(run.input().path("apiToken").stringValue()).isEqualTo("***");
        assertThat(run.output().path("status").stringValue())
                .isEqualTo("NO_EVIDENCE");
        assertThat(run.output().path("citations").isArray()).isTrue();
    }

    @Test
    void derivesValidatedCitationsAndCompletesTheGroundedRun() {
        Fixture fixture = release("grounded");
        var hit = new KnowledgeRetrievalHit(
                1,
                new BigDecimal("0.94"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                digest('b'),
                "Employees may claim up to 500 CNY per night.",
                "Travel policy",
                KnowledgeSource.Type.PDF,
                2,
                "Accommodation",
                3,
                10,
                12);
        when(retrieval.retrieveForRun(
                        eq(WORKSPACE_ID),
                        any(),
                        eq(fixture.index().id()),
                        eq(fixture.policy().id()),
                        eq("What is the hotel limit?")))
                .thenReturn(new KnowledgeRuntimeRetrievalResult(
                        new KnowledgeRetrievalResult(
                                KnowledgeRetrievalResult.Status.MATCHES,
                                fixture.index().id(),
                                fixture.policy().id(),
                                digest('c'),
                                List.of(hit),
                                4),
                        1,
                        true,
                        false));
        var input = json.createObjectNode().put("message", "What is the hotel limit?");

        var run = runs.execute(
                WORKSPACE_ID,
                fixture.release().applicationId(),
                new ExecuteRunCommand(
                        fixture.release().id(), input, "p23d-test"));

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.failureCode()).isNull();
        assertThat(run.providerId()).isEqualTo("local-deterministic");
        assertThat(run.governanceReservationId()).isNotNull();
        assertThat(run.output().path("status").stringValue()).isEqualTo("GROUNDED");
        assertThat(run.output().path("answer").stringValue())
                .contains("What is the hotel limit?");
        assertThat(run.output().path("citations").get(0).path("marker").stringValue())
                .isEqualTo("[K1]");
        assertThat(run.output().path("citations").get(0).has("locator")).isFalse();
        ArgumentCaptor<KnowledgeCommandContext> retrievalContext =
                ArgumentCaptor.forClass(KnowledgeCommandContext.class);
        verify(retrieval).retrieveForRun(
                eq(WORKSPACE_ID),
                retrievalContext.capture(),
                eq(fixture.index().id()),
                eq(fixture.policy().id()),
                eq("What is the hotel limit?"));
        assertThat(retrievalContext.getValue().traceId())
                .isEqualTo(run.traceId() + ":rag:0")
                .isNotEqualTo(run.traceId());
        assertThat(sql.queryForObject(
                        """
                        select count(*) from ai_run_retrieval_hit
                        where run_id = ? and marker = '[K1]' and citation_validated
                        """,
                        Integer.class,
                        run.id()))
                .isEqualTo(1);
        assertThat(citations.list(WORKSPACE_ID, run.id()))
                .singleElement()
                .satisfies(citation -> {
                    assertThat(citation.marker()).isEqualTo("[K1]");
                    assertThat(citation.indexVersion())
                            .isEqualTo(fixture.index().reference());
                    assertThat(citation.contentDigest()).isEqualTo(hit.contentDigest());
                    assertThat(citation.locator())
                            .isEqualTo("/api/v1/knowledge-source-revisions/"
                                    + hit.sourceRevisionId()
                                    + "/content#page=2&paragraph=3&lines=10-12");
                });
        assertThatThrownBy(() -> citations.list(UUID.randomUUID(), run.id()))
                .isInstanceOf(RunEvidenceException.class)
                .hasMessage("APVERO_RUNTIME_RUN_NOT_FOUND");
        assertThat(sql.queryForObject(
                        """
                        select status from execution_reservation_component
                        where reservation_id = ? and component_type = 'CHAT_GENERATION'
                        """,
                        String.class,
                        run.governanceReservationId()))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void chargesKnownUsageButFailsWithoutValidatingAFabricatedMarker() {
        Fixture fixture = release("fabricated-marker");
        var hit = new KnowledgeRetrievalHit(
                1,
                new BigDecimal("0.90"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                digest('6'),
                "Only K1 is available.",
                "Security policy",
                KnowledgeSource.Type.TEXT,
                null,
                null,
                1,
                1,
                1);
        when(retrieval.retrieveForRun(
                        eq(WORKSPACE_ID),
                        any(),
                        eq(fixture.index().id()),
                        eq(fixture.policy().id()),
                        eq("Fabricate a marker")))
                .thenReturn(new KnowledgeRuntimeRetrievalResult(
                        new KnowledgeRetrievalResult(
                                KnowledgeRetrievalResult.Status.MATCHES,
                                fixture.index().id(),
                                fixture.policy().id(),
                                digest('7'),
                                List.of(hit),
                                3),
                        1,
                        true,
                        false));
        RuntimeProvider provider = mock(RuntimeProvider.class);
        when(provider.id()).thenReturn("fabricated-marker-provider");
        when(provider.execute(any())).thenReturn(new ProviderResult(
                json.createObjectNode().put(
                        "message",
                        "{\"schemaVersion\":\"1.0\",\"status\":\"GROUNDED\","
                                + "\"answer\":\"Unsafe\",\"citationMarkers\":[\"[K999]\"]}"),
                11,
                7,
                23));
        when(providers.resolve(fixture.release())).thenReturn(provider);

        var run = runs.execute(
                WORKSPACE_ID,
                fixture.release().applicationId(),
                new ExecuteRunCommand(
                        fixture.release().id(),
                        json.createObjectNode().put("message", "Fabricate a marker"),
                        "p23e-test"));

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureCode())
                .isEqualTo("APVERO_CITATION_VALIDATION_FAILED");
        assertThat(run.promptTokens()).isEqualTo(11);
        assertThat(run.completionTokens()).isEqualTo(7);
        assertThat(run.costMicros()).isEqualTo(23);
        assertThat(sql.queryForObject(
                        """
                        select count(*) from ai_run_retrieval_hit
                        where run_id = ? and citation_validated
                        """,
                        Integer.class,
                        run.id()))
                .isZero();
        assertThat(citations.list(WORKSPACE_ID, run.id())).isEmpty();
    }

    @Test
    void distinguishesKnowledgeDisabledMalformedOutputAndSafeProviderFailure() {
        Fixture disabledFixture = release("knowledge-disabled");
        when(retrieval.retrieveForRun(
                        eq(WORKSPACE_ID),
                        any(),
                        eq(disabledFixture.index().id()),
                        eq(disabledFixture.policy().id()),
                        eq("Disabled")))
                .thenThrow(new KnowledgeDisabledException());
        var disabled = runs.execute(
                WORKSPACE_ID,
                disabledFixture.release().applicationId(),
                new ExecuteRunCommand(
                        disabledFixture.release().id(),
                        json.createObjectNode().put("message", "Disabled"),
                        "p23f-test"));

        Fixture malformedFixture = release("malformed-output");
        stubMatch(malformedFixture, "Malformed", '1');
        RuntimeProvider malformedProvider = mock(RuntimeProvider.class);
        when(malformedProvider.id()).thenReturn("malformed-provider");
        when(malformedProvider.execute(any())).thenReturn(new ProviderResult(
                json.createObjectNode().put("message", "not-json"),
                13,
                5,
                17));
        when(providers.resolve(malformedFixture.release()))
                .thenReturn(malformedProvider);
        var malformed = runs.execute(
                WORKSPACE_ID,
                malformedFixture.release().applicationId(),
                new ExecuteRunCommand(
                        malformedFixture.release().id(),
                        json.createObjectNode().put("message", "Malformed"),
                        "p23f-test"));

        Fixture providerFixture = release("safe-provider-failure");
        stubMatch(providerFixture, "Provider failure", '2');
        RuntimeProvider failedProvider = mock(RuntimeProvider.class);
        when(failedProvider.id()).thenReturn("safe-failure-provider");
        when(failedProvider.execute(any())).thenThrow(new ProviderExecutionException(
                "APVERO_PROVIDER_REQUEST_REJECTED",
                ProviderFailureDisposition.SAFE_TO_FAIL));
        when(providers.resolve(providerFixture.release()))
                .thenReturn(failedProvider);
        var providerFailure = runs.execute(
                WORKSPACE_ID,
                providerFixture.release().applicationId(),
                new ExecuteRunCommand(
                        providerFixture.release().id(),
                        json.createObjectNode().put("message", "Provider failure"),
                        "p23f-test"));

        assertThat(disabled.status()).isEqualTo(RunStatus.FAILED);
        assertThat(disabled.failureCode()).isEqualTo("APVERO_KNOWLEDGE_DISABLED");
        assertThat(disabled.governanceReservationId()).isNull();
        assertThat(malformed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(malformed.failureCode())
                .isEqualTo("APVERO_GROUNDED_OUTPUT_INVALID");
        assertThat(malformed.promptTokens()).isEqualTo(13);
        assertThat(malformed.completionTokens()).isEqualTo(5);
        assertThat(malformed.costMicros()).isEqualTo(17);
        assertThat(providerFailure.status()).isEqualTo(RunStatus.FAILED);
        assertThat(providerFailure.failureCode())
                .isEqualTo("APVERO_PROVIDER_REQUEST_REJECTED");
        assertThat(providerFailure.promptTokens()).isZero();
        assertThat(providerFailure.completionTokens()).isZero();
        assertThat(providerFailure.costMicros()).isZero();
    }

    @Test
    void executesOnlyTheImmutableReleaseAndReadsRetainedLineageWithoutRuntimeMemory() {
        Fixture fixture = release("immutable-runtime");
        var hit = stubMatch(fixture, "Pinned release", '3');
        var mutable = applications.get(
                WORKSPACE_ID, fixture.release().applicationId());
        applications.replaceDraftKnowledgeBindings(
                WORKSPACE_ID,
                mutable.id(),
                new ReplaceApplicationKnowledgeBindingsCommand(
                        mutable.version(),
                        List.of(new ReplaceApplicationKnowledgeBindingsCommand.BindingSelection(
                                UUID.randomUUID(), UUID.randomUUID()))));

        var run = runs.execute(
                WORKSPACE_ID,
                fixture.release().applicationId(),
                new ExecuteRunCommand(
                        fixture.release().id(),
                        json.createObjectNode().put("message", "Pinned release"),
                        "p23f-test"));
        reset(indexes, policies, retrieval, providers);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(runs.list(WORKSPACE_ID))
                .extracting(io.apvero.platform.runtime.RunRecord::id)
                .contains(run.id());
        assertThat(citations.list(WORKSPACE_ID, run.id()))
                .singleElement()
                .satisfies(citation -> {
                    assertThat(citation.sourceRevisionId())
                            .isEqualTo(hit.sourceRevisionId());
                    assertThat(citation.contentDigest())
                            .isEqualTo(hit.contentDigest());
                });
    }

    @Test
    void canDiscardPayloadsWithoutDiscardingVerifiedCitationLineage() {
        Fixture fixture = release("no-payload-retention");
        var hit = stubMatch(fixture, "Do not retain payload", '4');
        retentionPolicies.update(WORKSPACE_ID, 30, 365, false, true);

        var run = runs.execute(
                WORKSPACE_ID,
                fixture.release().applicationId(),
                new ExecuteRunCommand(
                        fixture.release().id(),
                        json.createObjectNode()
                                .put("message", "Do not retain payload")
                                .put("apiToken", "must-not-remain"),
                        "p23f-test"));

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.input().path("retained").booleanValue()).isFalse();
        assertThat(run.output().path("retained").booleanValue()).isFalse();
        assertThat(run.input().toString()).doesNotContain("must-not-remain");
        assertThat(citations.list(WORKSPACE_ID, run.id()))
                .singleElement()
                .satisfies(citation -> {
                    assertThat(citation.sourceRevisionId())
                            .isEqualTo(hit.sourceRevisionId());
                    assertThat(citation.locator()).contains(hit.sourceRevisionId().toString());
                });
    }

    @Test
    void requiresReconciliationWhenProviderOutcomeIsAmbiguous() {
        Fixture fixture = release("ambiguous-provider");
        var hit = new KnowledgeRetrievalHit(
                1,
                new BigDecimal("0.93"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                digest('7'),
                "Evidence was dispatched with the request.",
                "Incident policy",
                KnowledgeSource.Type.TEXT,
                null,
                null,
                1,
                1,
                1);
        when(retrieval.retrieveForRun(
                        eq(WORKSPACE_ID),
                        any(),
                        eq(fixture.index().id()),
                        eq(fixture.policy().id()),
                        eq("Trigger ambiguous outcome")))
                .thenReturn(new KnowledgeRuntimeRetrievalResult(
                        new KnowledgeRetrievalResult(
                                KnowledgeRetrievalResult.Status.MATCHES,
                                fixture.index().id(),
                                fixture.policy().id(),
                                digest('8'),
                                List.of(hit),
                                5),
                        1,
                        true,
                        false));
        when(providers.resolve(fixture.release())).thenReturn(new RuntimeProvider() {
            @Override
            public String id() {
                return "ambiguous-test-provider";
            }

            @Override
            public boolean supports(ReleaseBundle release) {
                return true;
            }

            @Override
            public ProviderResult execute(ProviderRequest request) {
                throw new IllegalStateException("transport outcome unknown");
            }
        });

        var run = runs.execute(
                WORKSPACE_ID,
                fixture.release().applicationId(),
                new ExecuteRunCommand(
                        fixture.release().id(),
                        json.createObjectNode()
                                .put("message", "Trigger ambiguous outcome"),
                        "p23d-test"));

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureCode())
                .isEqualTo("APVERO_EXTERNAL_OUTCOME_RECONCILIATION_REQUIRED");
        assertThat(sql.queryForObject(
                        """
                        select status from execution_reservation_component
                        where reservation_id = ? and component_type = 'CHAT_GENERATION'
                        """,
                        String.class,
                        run.governanceReservationId()))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(sql.queryForObject(
                        "select count(*) from ai_run_retrieval where run_id = ?",
                        Integer.class,
                        run.id()))
                .isEqualTo(1);
    }

    @Test
    void releasesTheChatReservationWhenRunAttachmentFailsBeforeProviderDispatch() {
        Fixture fixture = release("attachment-failure");
        var hit = new KnowledgeRetrievalHit(
                1,
                new BigDecimal("0.92"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                digest('4'),
                "Evidence remains available.",
                "Operations policy",
                KnowledgeSource.Type.TEXT,
                null,
                null,
                1,
                1,
                1);
        when(retrieval.retrieveForRun(
                        eq(WORKSPACE_ID),
                        any(),
                        eq(fixture.index().id()),
                        eq(fixture.policy().id()),
                        eq("Fail before provider dispatch")))
                .thenReturn(new KnowledgeRuntimeRetrievalResult(
                        new KnowledgeRetrievalResult(
                                KnowledgeRetrievalResult.Status.MATCHES,
                                fixture.index().id(),
                                fixture.policy().id(),
                                digest('5'),
                                List.of(hit),
                                4),
                        1,
                        true,
                        false));
        RuntimeProvider provider = mock(RuntimeProvider.class);
        when(provider.id()).thenReturn("never-dispatched-provider");
        when(providers.resolve(fixture.release())).thenReturn(provider);
        doThrow(new IllegalStateException("attachment unavailable"))
                .doCallRealMethod()
                .when(lifecycle)
                .attachChat(any(), any(), any(), any());

        var run = runs.execute(
                WORKSPACE_ID,
                fixture.release().applicationId(),
                new ExecuteRunCommand(
                        fixture.release().id(),
                        json.createObjectNode()
                                .put("message", "Fail before provider dispatch"),
                        "p23d-test"));

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureCode())
                .isEqualTo("APVERO_RUNTIME_CHAT_DISPATCH_PREPARATION_FAILED");
        assertThat(sql.queryForObject(
                        """
                        select c.status
                        from execution_reservation_component c
                        join execution_reservation r on r.id = c.reservation_id
                        where r.trace_id = ? and c.component_type = 'CHAT_GENERATION'
                        """,
                        String.class,
                        run.traceId()))
                .isEqualTo("RELEASED");
        verify(provider, never()).execute(any());
    }

    @Test
    void preservesEarlierOrderedEvidenceWhenALaterExactBindingCannotResolve() {
        MultiFixture fixture = releaseWithTwoBindings("partial-evidence");
        var firstHit = new KnowledgeRetrievalHit(
                1,
                new BigDecimal("0.91"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                digest('f'),
                "First binding evidence.",
                "First policy",
                KnowledgeSource.Type.MARKDOWN,
                null,
                "Scope",
                1,
                1,
                1);
        when(retrieval.retrieveForRun(
                        eq(WORKSPACE_ID),
                        any(),
                        eq(fixture.firstIndex().id()),
                        eq(fixture.firstPolicy().id()),
                        eq("Resolve in order")))
                .thenReturn(new KnowledgeRuntimeRetrievalResult(
                        new KnowledgeRetrievalResult(
                                KnowledgeRetrievalResult.Status.MATCHES,
                                fixture.firstIndex().id(),
                                fixture.firstPolicy().id(),
                                digest('1'),
                                List.of(firstHit),
                                2),
                        1,
                        true,
                        false));
        when(indexes.getByReference(
                        WORKSPACE_ID, fixture.secondIndex().reference()))
                .thenThrow(new KnowledgeException(
                        "APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND",
                        KnowledgeException.Category.NOT_FOUND));

        var run = runs.execute(
                WORKSPACE_ID,
                fixture.release().applicationId(),
                new ExecuteRunCommand(
                        fixture.release().id(),
                        json.createObjectNode().put("message", "Resolve in order"),
                        "p23d-test"));

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureCode())
                .isEqualTo("APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND");
        assertThat(sql.queryForObject(
                        "select count(*) from ai_run_retrieval where run_id = ?",
                        Integer.class,
                        run.id()))
                .isEqualTo(1);
        assertThat(sql.queryForObject(
                        "select \"sequence\" from ai_run_retrieval where run_id = ?",
                        Integer.class,
                        run.id()))
                .isZero();
        assertThat(sql.queryForObject(
                        "select index_version_reference from ai_run_retrieval where run_id = ?",
                        String.class,
                        run.id()))
                .isEqualTo(fixture.firstIndex().reference());
        verify(retrieval, never()).retrieveForRun(
                eq(WORKSPACE_ID),
                any(),
                eq(fixture.secondIndex().id()),
                eq(fixture.secondPolicy().id()),
                any());
    }

    private Fixture release(String prefix) {
        var created = applications.create(
                WORKSPACE_ID,
                new CreateApplicationCommand(
                        prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                        "P2.3d RAG",
                        "",
                        RuntimeMode.RAG));
        var bound = applications.bindDraft(
                WORKSPACE_ID,
                created.id(),
                new BindApplicationDraftCommand(ROUTE_ID, PROMPT_VERSION_ID));
        UUID indexId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        applications.replaceDraftKnowledgeBindings(
                WORKSPACE_ID,
                bound.id(),
                new ReplaceApplicationKnowledgeBindingsCommand(
                        bound.version(),
                        List.of(new ReplaceApplicationKnowledgeBindingsCommand.BindingSelection(
                                indexId, policyId))));
        KnowledgeIndexVersion index = index(indexId);
        RetrievalPolicyVersion policy = policy(policyId);
        when(indexes.get(WORKSPACE_ID, indexId)).thenReturn(index);
        when(indexes.getByReference(WORKSPACE_ID, index.reference())).thenReturn(index);
        when(policies.get(WORKSPACE_ID, policyId)).thenReturn(policy);
        when(policies.getByReference(WORKSPACE_ID, policy.reference())).thenReturn(policy);
        when(policies.supportsExecution(policy)).thenReturn(true);
        ReleaseBundle release = releases.create(
                WORKSPACE_ID,
                bound.id(),
                new CreateReleaseCommand("2.3.0-" + prefix));
        when(providers.resolve(release))
                .thenReturn(new DeterministicLocalProvider(json));
        return new Fixture(release, index, policy);
    }

    private KnowledgeRetrievalHit stubMatch(
            Fixture fixture, String query, char digestValue) {
        var hit = new KnowledgeRetrievalHit(
                1,
                new BigDecimal("0.91"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                digest(digestValue),
                "Immutable retained evidence for " + query,
                "Compatibility evidence",
                KnowledgeSource.Type.TEXT,
                null,
                "Evidence",
                1,
                1,
                1);
        when(retrieval.retrieveForRun(
                        eq(WORKSPACE_ID),
                        any(),
                        eq(fixture.index().id()),
                        eq(fixture.policy().id()),
                        eq(query)))
                .thenReturn(new KnowledgeRuntimeRetrievalResult(
                        new KnowledgeRetrievalResult(
                                KnowledgeRetrievalResult.Status.MATCHES,
                                fixture.index().id(),
                                fixture.policy().id(),
                                digest((char) (digestValue + 1)),
                                List.of(hit),
                                3),
                        1,
                        true,
                        false));
        return hit;
    }

    private MultiFixture releaseWithTwoBindings(String prefix) {
        var created = applications.create(
                WORKSPACE_ID,
                new CreateApplicationCommand(
                        prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                        "P2.3d ordered RAG",
                        "",
                        RuntimeMode.RAG));
        var bound = applications.bindDraft(
                WORKSPACE_ID,
                created.id(),
                new BindApplicationDraftCommand(ROUTE_ID, PROMPT_VERSION_ID));
        UUID firstIndexId = UUID.randomUUID();
        UUID firstPolicyId = UUID.randomUUID();
        UUID secondIndexId = UUID.randomUUID();
        UUID secondPolicyId = UUID.randomUUID();
        KnowledgeIndexVersion firstIndex =
                index(firstIndexId, "employee-index@1.0.0");
        RetrievalPolicyVersion firstPolicy =
                policy(firstPolicyId, "exact-policy@1.0.0");
        KnowledgeIndexVersion secondIndex =
                index(secondIndexId, "finance-index@2.0.0");
        RetrievalPolicyVersion secondPolicy =
                policy(secondPolicyId, "strict-policy@2.0.0");
        when(indexes.get(WORKSPACE_ID, firstIndexId)).thenReturn(firstIndex);
        when(indexes.get(WORKSPACE_ID, secondIndexId)).thenReturn(secondIndex);
        when(policies.get(WORKSPACE_ID, firstPolicyId)).thenReturn(firstPolicy);
        when(policies.get(WORKSPACE_ID, secondPolicyId)).thenReturn(secondPolicy);
        when(policies.supportsExecution(firstPolicy)).thenReturn(true);
        when(policies.supportsExecution(secondPolicy)).thenReturn(true);
        applications.replaceDraftKnowledgeBindings(
                WORKSPACE_ID,
                bound.id(),
                new ReplaceApplicationKnowledgeBindingsCommand(
                        bound.version(),
                        List.of(
                                new ReplaceApplicationKnowledgeBindingsCommand.BindingSelection(
                                        firstIndexId, firstPolicyId),
                                new ReplaceApplicationKnowledgeBindingsCommand.BindingSelection(
                                        secondIndexId, secondPolicyId))));
        when(indexes.getByReference(WORKSPACE_ID, firstIndex.reference()))
                .thenReturn(firstIndex);
        when(policies.getByReference(WORKSPACE_ID, firstPolicy.reference()))
                .thenReturn(firstPolicy);
        ReleaseBundle release = releases.create(
                WORKSPACE_ID,
                bound.id(),
                new CreateReleaseCommand("2.3.0-" + prefix));
        when(providers.resolve(release))
                .thenReturn(new DeterministicLocalProvider(json));
        return new MultiFixture(
                release,
                firstIndex,
                firstPolicy,
                secondIndex,
                secondPolicy);
    }

    private KnowledgeIndexVersion index(UUID id) {
        return index(id, "employee-index@1.0.0");
    }

    private KnowledgeIndexVersion index(UUID id, String reference) {
        return new KnowledgeIndexVersion(
                id,
                TENANT_ID,
                WORKSPACE_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                reference.substring(reference.indexOf('@') + 1),
                reference,
                UUID.randomUUID(),
                "embedding@1",
                3,
                1,
                1,
                digest('d'),
                KnowledgeIndexVersion.Status.READY,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private RetrievalPolicyVersion policy(UUID id) {
        return policy(id, "exact-policy@1.0.0");
    }

    private RetrievalPolicyVersion policy(UUID id, String reference) {
        return new RetrievalPolicyVersion(
                id,
                TENANT_ID,
                WORKSPACE_ID,
                reference.substring(0, reference.indexOf('@')),
                reference.substring(reference.indexOf('@') + 1),
                reference,
                5,
                4096,
                new BigDecimal("0.5"),
                RetrievalPolicyOverlapHandling.KEEP,
                "exact-cosine@1.0.0",
                "apvero-utf8-byte@1.0.0",
                1,
                digest('e'),
                "NO_EVIDENCE",
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(
            ReleaseBundle release,
            KnowledgeIndexVersion index,
            RetrievalPolicyVersion policy) {}

    private record MultiFixture(
            ReleaseBundle release,
            KnowledgeIndexVersion firstIndex,
            RetrievalPolicyVersion firstPolicy,
            KnowledgeIndexVersion secondIndex,
            RetrievalPolicyVersion secondPolicy) {}

    @TestConfiguration
    static class RuntimeStubConfiguration {
        @Bean("p23KnowledgeRuntime")
        @Primary
        KnowledgeRuntimeRetrieval p23KnowledgeRuntime() {
            return mock(KnowledgeRuntimeRetrieval.class);
        }
    }
}
