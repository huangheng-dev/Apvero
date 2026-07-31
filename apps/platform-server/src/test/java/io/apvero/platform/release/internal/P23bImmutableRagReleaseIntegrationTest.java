package io.apvero.platform.release.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.application.BindApplicationDraftCommand;
import io.apvero.platform.application.CreateApplicationCommand;
import io.apvero.platform.application.ReplaceApplicationKnowledgeBindingsCommand;
import io.apvero.platform.application.RuntimeMode;
import io.apvero.platform.capability.CapabilityCatalog;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeIndexVersion;
import io.apvero.platform.knowledge.KnowledgeIndexVersionCatalog;
import io.apvero.platform.knowledge.RetrievalPolicyOverlapHandling;
import io.apvero.platform.knowledge.RetrievalPolicyVersion;
import io.apvero.platform.knowledge.RetrievalPolicyVersionCatalog;
import io.apvero.platform.release.CreateReleaseCommand;
import io.apvero.platform.release.ReleaseCatalog;
import io.apvero.platform.release.ReleaseException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
class P23bImmutableRagReleaseIntegrationTest {
    static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID WORKSPACE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    static final UUID ROUTE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000003201");
    static final UUID PROMPT_VERSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000004101");
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p23b_test")
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
    @Autowired CapabilityCatalog capabilities;
    @Autowired ReleaseCatalog releases;
    @Autowired JdbcTemplate sql;
    @MockitoBean KnowledgeIndexVersionCatalog indexes;
    @MockitoBean RetrievalPolicyVersionCatalog policies;

    @Test
    void resolvesOpaquePinsAndPersistsOneImmutableServerManifest() {
        UUID embeddingRouteId = insertEmbeddingRoute();
        assertThat(capabilities.listRoutes(WORKSPACE_ID))
                .extracting(io.apvero.platform.capability.ModelRoute::id)
                .contains(ROUTE_ID)
                .doesNotContain(embeddingRouteId);

        var application = ragApplication("release");
        UUID indexId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        bind(application.id(), application.version(), indexId, policyId);
        var index = index(indexId, "support-index@1.0.0");
        var policy = policy(policyId, "exact@1.0.0");
        when(indexes.get(WORKSPACE_ID, indexId)).thenReturn(index);
        when(policies.get(WORKSPACE_ID, policyId)).thenReturn(policy);
        when(policies.supportsExecution(policy)).thenReturn(true);

        var release = releases.create(
                WORKSPACE_ID, application.id(), new CreateReleaseCommand("2.3.0"));

        assertThat(release.manifest().path("schemaVersion").stringValue()).isEqualTo("1.1");
        assertThat(release.manifest().path("knowledgeBindings").get(0)
                        .path("indexVersion").stringValue())
                .isEqualTo("support-index@1.0.0");
        assertThat(release.manifest().path("knowledgeBindings").get(0)
                        .path("retrievalPolicyVersion").stringValue())
                .isEqualTo("exact@1.0.0");
        assertThat(release.artifactDigest()).hasSize(64);
        assertThat(sql.queryForObject(
                        "select manifest ->> 'schemaVersion' from release_bundle where id = ?",
                        String.class,
                        release.id()))
                .isEqualTo("1.1");
        assertThat(releases.get(WORKSPACE_ID, release.id())).isEqualTo(release);
        assertThatThrownBy(() -> sql.update(
                        "update release_bundle set version = '9.9.9' where id = ?",
                        release.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    private UUID insertEmbeddingRoute() {
        UUID modelId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        sql.update("""
                insert into model_definition(
                    id, tenant_id, workspace_id, provider_id, model_key, name,
                    capabilities, input_cost_micros_per_million,
                    output_cost_micros_per_million, enabled, created_at, updated_at)
                values (?, ?, ?, '00000000-0000-0000-0000-000000003001'::uuid,
                    ?, 'P2.3b Embedding', '["EMBEDDING"]'::jsonb, 0, 0, true, now(), now())
                """, modelId, TENANT_ID, WORKSPACE_ID, "p23b-embedding-" + modelId);
        sql.update("""
                insert into model_route(
                    id, tenant_id, workspace_id, name, version, model_id, status,
                    timeout_ms, route_capability, embedding_dimension,
                    embedding_maximum_input_tokens, embedding_maximum_batch_size,
                    embedding_normalization, created_at)
                values (?, ?, ?, ?, 1, ?, 'PUBLISHED', 2000, 'EMBEDDING',
                    3, 8192, 64, 'L2', now())
                """, routeId, TENANT_ID, WORKSPACE_ID, "p23b-embedding-" + routeId, modelId);
        return routeId;
    }

    @Test
    void rollsBackReleaseWhenAnyOpaquePinFailsAuthoritativeResolution() {
        var application = ragApplication("rollback");
        UUID validIndexId = UUID.randomUUID();
        UUID validPolicyId = UUID.randomUUID();
        UUID missingIndexId = UUID.randomUUID();
        UUID missingPolicyId = UUID.randomUUID();
        var current = applications.get(WORKSPACE_ID, application.id());
        applications.replaceDraftKnowledgeBindings(
                WORKSPACE_ID,
                application.id(),
                new ReplaceApplicationKnowledgeBindingsCommand(
                        current.version(),
                        List.of(
                                new ReplaceApplicationKnowledgeBindingsCommand.BindingSelection(
                                        validIndexId, validPolicyId),
                                new ReplaceApplicationKnowledgeBindingsCommand.BindingSelection(
                                        missingIndexId, missingPolicyId))));
        var index = index(validIndexId, "valid-index@1.0.0");
        var policy = policy(validPolicyId, "exact@1.0.0");
        when(indexes.get(WORKSPACE_ID, validIndexId)).thenReturn(index);
        when(policies.get(WORKSPACE_ID, validPolicyId)).thenReturn(policy);
        when(policies.supportsExecution(policy)).thenReturn(true);
        when(indexes.get(WORKSPACE_ID, missingIndexId)).thenThrow(new KnowledgeException(
                "APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND",
                KnowledgeException.Category.NOT_FOUND));

        assertThatThrownBy(() -> releases.create(
                        WORKSPACE_ID,
                        application.id(),
                        new CreateReleaseCommand("2.3.1")))
                .isInstanceOf(ReleaseException.class)
                .hasMessage("APVERO_RELEASE_KNOWLEDGE_BINDING_INVALID");
        assertThat(sql.queryForObject(
                        "select count(*) from release_bundle where application_id = ?",
                        Integer.class,
                        application.id()))
                .isZero();
    }

    private io.apvero.platform.application.AiApplication ragApplication(String prefix) {
        var created = applications.create(
                WORKSPACE_ID,
                new CreateApplicationCommand(
                        prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                        "P2.3b RAG",
                        "",
                        RuntimeMode.RAG));
        return applications.bindDraft(
                WORKSPACE_ID,
                created.id(),
                new BindApplicationDraftCommand(ROUTE_ID, PROMPT_VERSION_ID));
    }

    private void bind(
            UUID applicationId,
            long applicationVersion,
            UUID indexId,
            UUID policyId) {
        applications.replaceDraftKnowledgeBindings(
                WORKSPACE_ID,
                applicationId,
                new ReplaceApplicationKnowledgeBindingsCommand(
                        applicationVersion,
                        List.of(new ReplaceApplicationKnowledgeBindingsCommand.BindingSelection(
                                indexId, policyId))));
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
                ROUTE_ID,
                "embedding@1",
                8,
                1,
                1,
                "sha256:" + "a".repeat(64),
                KnowledgeIndexVersion.Status.READY,
                OffsetDateTime.now(ZoneOffset.UTC));
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
                2048,
                new BigDecimal("0.5"),
                RetrievalPolicyOverlapHandling.COLLAPSE_ADJACENT,
                "exact-cosine@1.0.0",
                "apvero-utf8-byte@1.0.0",
                1,
                "sha256:" + "b".repeat(64),
                "NO_EVIDENCE",
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
