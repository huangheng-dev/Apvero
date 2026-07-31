package io.apvero.platform.release.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.application.ApplicationKnowledgeBinding;
import io.apvero.platform.application.ApplicationKnowledgeBindingSet;
import io.apvero.platform.application.ApplicationStatus;
import io.apvero.platform.application.RuntimeMode;
import io.apvero.platform.capability.CapabilityCatalog;
import io.apvero.platform.capability.ModelRoute;
import io.apvero.platform.knowledge.KnowledgeIndexVersion;
import io.apvero.platform.knowledge.KnowledgeIndexVersionCatalog;
import io.apvero.platform.knowledge.RetrievalPolicyOverlapHandling;
import io.apvero.platform.knowledge.RetrievalPolicyVersion;
import io.apvero.platform.knowledge.RetrievalPolicyVersionCatalog;
import io.apvero.platform.release.CreateReleaseCommand;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleaseException;
import io.apvero.platform.release.ReleasePurpose;
import io.apvero.platform.release.ReleaseStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DefaultReleaseCatalogP23bTest {
    private final ApplicationCatalog applications = mock(ApplicationCatalog.class);
    private final ReleaseRepository repository = mock(ReleaseRepository.class);
    private final CapabilityCatalog capabilities = mock(CapabilityCatalog.class);
    private final KnowledgeIndexVersionCatalog indexes = mock(KnowledgeIndexVersionCatalog.class);
    private final RetrievalPolicyVersionCatalog policies = mock(RetrievalPolicyVersionCatalog.class);
    private final JsonMapper json = new JsonMapper();
    private final DefaultReleaseCatalog catalog = new DefaultReleaseCatalog(
            applications,
            repository,
            new ReleaseManifestValidator(),
            new ReleaseArtifactDigester(json),
            capabilities,
            indexes,
            policies,
            new ReleasePinTelemetry(new SimpleMeterRegistry()),
            json);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private final UUID routeId = UUID.randomUUID();
    private final UUID promptVersionId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    @BeforeEach
    void persistCapturedManifest() {
        when(repository.insert(
                        any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new ReleaseBundle(
                        UUID.randomUUID(),
                        tenantId,
                        workspaceId,
                        applicationId,
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        ReleaseStatus.RELEASED,
                        invocation.getArgument(4),
                        invocation.getArgument(5),
                        now));
    }

    @Test
    void createsServerAuthoritativeOrderedRagManifestAndDigest() {
        AiApplication application = application(RuntimeMode.RAG, 7);
        ModelRoute route = route();
        var first = binding(0);
        var second = binding(1);
        var firstIndex = index(first.indexVersionId(), "support-a@1.0.0");
        var secondIndex = index(second.indexVersionId(), "support-b@2.0.0");
        var sharedPolicy = policy(first.retrievalPolicyVersionId(), "exact@1.0.0");
        var secondPolicy = policy(second.retrievalPolicyVersionId(), "strict@2.0.0");
        when(applications.get(workspaceId, applicationId)).thenReturn(application);
        when(applications.getDraftKnowledgeBindings(workspaceId, applicationId))
                .thenReturn(new ApplicationKnowledgeBindingSet(
                        applicationId, 7, List.of(first, second)));
        when(capabilities.listRoutes(workspaceId)).thenReturn(List.of(route));
        when(capabilities.promptVersionReference(workspaceId, promptVersionId))
                .thenReturn("grounded@4");
        when(indexes.get(workspaceId, first.indexVersionId())).thenReturn(firstIndex);
        when(indexes.get(workspaceId, second.indexVersionId())).thenReturn(secondIndex);
        when(policies.get(workspaceId, first.retrievalPolicyVersionId()))
                .thenReturn(sharedPolicy);
        when(policies.get(workspaceId, second.retrievalPolicyVersionId()))
                .thenReturn(secondPolicy);
        when(policies.supportsExecution(sharedPolicy)).thenReturn(true);
        when(policies.supportsExecution(secondPolicy)).thenReturn(true);

        ReleaseBundle release =
                catalog.create(workspaceId, applicationId, new CreateReleaseCommand("2.3.0"));

        JsonNode manifest = release.manifest();
        assertThat(manifest.path("schemaVersion").stringValue()).isEqualTo("1.1");
        assertThat(manifest.path("runtimeMode").stringValue()).isEqualTo("RAG");
        assertThat(manifest.path("modelRouteVersion").stringValue()).isEqualTo("answer@3");
        assertThat(manifest.path("promptVersion").stringValue()).isEqualTo("grounded@4");
        assertThat(manifest.path("knowledgeBindings").valueStream()
                        .map(node -> node.path("indexVersion").stringValue()))
                .containsExactly("support-a@1.0.0", "support-b@2.0.0");
        assertThat(manifest.path("policyVersions").valueStream()
                        .map(JsonNode::stringValue))
                .containsExactly("exact@1.0.0", "strict@2.0.0");
        assertThat(manifest.path("runtimeParameters").path("temperature").decimalValue())
                .isEqualByComparingTo("0.2");
        assertThat(manifest.path("runtimeParameters").path("maxOutputTokens").intValue())
                .isEqualTo(2048);
        assertThat(release.artifactDigest()).hasSize(64);
        assertThat(release.purpose()).isEqualTo(ReleasePurpose.PRODUCTION);
    }

    @Test
    void preservesLegacyChatManifestAndDoesNotReadKnowledgeBindings() {
        AiApplication application = application(RuntimeMode.CHAT, 2);
        when(applications.get(workspaceId, applicationId)).thenReturn(application);
        when(capabilities.listRoutes(workspaceId)).thenReturn(List.of(route()));
        when(capabilities.promptVersionReference(workspaceId, promptVersionId))
                .thenReturn("chat@2");

        ReleaseBundle release =
                catalog.create(workspaceId, applicationId, new CreateReleaseCommand("1.5.0"));

        assertThat(release.manifest().path("schemaVersion").stringValue()).isEqualTo("1.0");
        assertThat(release.manifest().path("knowledgeIndexVersions")).isEmpty();
        verify(applications, never()).getDraftKnowledgeBindings(any(), any());
        verify(indexes, never()).get(any(), any());
    }

    @Test
    void rejectsEmptyStaleOutOfOrderAndUnsupportedKnowledgeSelections() {
        AiApplication application = application(RuntimeMode.RAG, 7);
        when(applications.get(workspaceId, applicationId)).thenReturn(application);
        when(capabilities.listRoutes(workspaceId)).thenReturn(List.of(route()));
        when(capabilities.promptVersionReference(workspaceId, promptVersionId))
                .thenReturn("grounded@1");

        when(applications.getDraftKnowledgeBindings(workspaceId, applicationId))
                .thenReturn(new ApplicationKnowledgeBindingSet(applicationId, 7, List.of()));
        assertBindingProblem();

        var selected = binding(0);
        when(applications.getDraftKnowledgeBindings(workspaceId, applicationId))
                .thenReturn(new ApplicationKnowledgeBindingSet(applicationId, 6, List.of(selected)));
        assertBindingProblem();

        when(applications.getDraftKnowledgeBindings(workspaceId, applicationId))
                .thenReturn(new ApplicationKnowledgeBindingSet(
                        applicationId,
                        7,
                        List.of(new ApplicationKnowledgeBinding(
                                selected.indexVersionId(),
                                selected.retrievalPolicyVersionId(),
                                1))));
        assertBindingProblem();

        var index = index(selected.indexVersionId(), "support@1.0.0");
        var policy = policy(selected.retrievalPolicyVersionId(), "legacy@1.0.0");
        when(applications.getDraftKnowledgeBindings(workspaceId, applicationId))
                .thenReturn(new ApplicationKnowledgeBindingSet(applicationId, 7, List.of(selected)));
        when(indexes.get(workspaceId, selected.indexVersionId())).thenReturn(index);
        when(policies.get(workspaceId, selected.retrievalPolicyVersionId())).thenReturn(policy);
        when(policies.supportsExecution(policy)).thenReturn(false);
        assertBindingProblem();
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void validatesStoredManifestSchemaOnRead() throws Exception {
        UUID releaseId = UUID.randomUUID();
        JsonNode unknown = json.readTree("{\"schemaVersion\":\"9.9\"}");
        when(repository.findById(workspaceId, releaseId)).thenReturn(java.util.Optional.of(
                new ReleaseBundle(
                        releaseId,
                        tenantId,
                        workspaceId,
                        applicationId,
                        "9.9.0",
                        "0".repeat(64),
                        unknown,
                        ReleaseStatus.RELEASED,
                        ReleasePurpose.PRODUCTION,
                        null,
                        now)));

        assertThatThrownBy(() -> catalog.get(workspaceId, releaseId))
                .isInstanceOf(ReleaseException.class)
                .hasMessage("APVERO_RELEASE_MANIFEST_UNSUPPORTED");
    }

    private void assertBindingProblem() {
        assertThatThrownBy(() ->
                        catalog.create(workspaceId, applicationId, new CreateReleaseCommand("2.3.0")))
                .isInstanceOf(ReleaseException.class)
                .hasMessage("APVERO_RELEASE_KNOWLEDGE_BINDING_INVALID");
    }

    private AiApplication application(RuntimeMode mode, long version) {
        return new AiApplication(
                applicationId,
                tenantId,
                workspaceId,
                "support",
                "Support",
                "",
                mode,
                ApplicationStatus.DRAFT,
                routeId,
                promptVersionId,
                version,
                now,
                now);
    }

    private ModelRoute route() {
        return new ModelRoute(
                routeId,
                tenantId,
                workspaceId,
                "answer",
                3,
                UUID.randomUUID(),
                "ACTIVE",
                30_000,
                2048,
                new BigDecimal("0.2"),
                now);
    }

    private ApplicationKnowledgeBinding binding(int order) {
        return new ApplicationKnowledgeBinding(
                UUID.randomUUID(), UUID.randomUUID(), order);
    }

    private KnowledgeIndexVersion index(UUID id, String reference) {
        return new KnowledgeIndexVersion(
                id,
                tenantId,
                workspaceId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                reference.substring(reference.indexOf('@') + 1),
                reference,
                routeId,
                "embedding@1",
                8,
                1,
                1,
                "sha256:" + "a".repeat(64),
                KnowledgeIndexVersion.Status.READY,
                now);
    }

    private RetrievalPolicyVersion policy(UUID id, String reference) {
        return new RetrievalPolicyVersion(
                id,
                tenantId,
                workspaceId,
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
                now);
    }
}
