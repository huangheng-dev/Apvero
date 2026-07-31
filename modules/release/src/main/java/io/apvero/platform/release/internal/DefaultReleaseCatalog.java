package io.apvero.platform.release.internal;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.application.ApplicationKnowledgeBinding;
import io.apvero.platform.application.ApplicationKnowledgeBindingSet;
import io.apvero.platform.application.RuntimeMode;
import io.apvero.platform.capability.CapabilityCatalog;
import io.apvero.platform.capability.ModelRoute;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeIndexVersion;
import io.apvero.platform.knowledge.KnowledgeIndexVersionCatalog;
import io.apvero.platform.knowledge.RetrievalPolicyVersion;
import io.apvero.platform.knowledge.RetrievalPolicyVersionCatalog;
import io.apvero.platform.release.CreateReleaseCommand;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleaseCatalog;
import io.apvero.platform.release.ReleaseException;
import io.apvero.platform.release.ReleaseNotFoundException;
import io.apvero.platform.release.ReleasePurpose;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional(readOnly = true)
public class DefaultReleaseCatalog implements ReleaseCatalog {
    private final ApplicationCatalog applications;
    private final ReleaseRepository repository;
    private final ReleaseManifestValidator validator;
    private final ReleaseArtifactDigester digester;
    private final CapabilityCatalog capabilities;
    private final KnowledgeIndexVersionCatalog indexVersions;
    private final RetrievalPolicyVersionCatalog policyVersions;
    private final ReleasePinTelemetry telemetry;
    private final ObjectMapper json;

    public DefaultReleaseCatalog(
            ApplicationCatalog applications,
            ReleaseRepository repository,
            ReleaseManifestValidator validator,
            ReleaseArtifactDigester digester,
            CapabilityCatalog capabilities,
            KnowledgeIndexVersionCatalog indexVersions,
            RetrievalPolicyVersionCatalog policyVersions,
            ReleasePinTelemetry telemetry,
            ObjectMapper json) {
        this.applications = applications;
        this.repository = repository;
        this.validator = validator;
        this.digester = digester;
        this.capabilities = capabilities;
        this.indexVersions = indexVersions;
        this.policyVersions = policyVersions;
        this.telemetry = telemetry;
        this.json = json;
    }

    @Override
    public List<ReleaseBundle> list(UUID workspaceId, UUID applicationId) {
        applications.get(workspaceId, applicationId);
        List<ReleaseBundle> releases = repository.findAll(workspaceId, applicationId);
        releases.forEach(release -> validator.validate(release.manifest()));
        return releases;
    }

    @Override
    public ReleaseBundle get(UUID workspaceId, UUID releaseId) {
        ReleaseBundle release = repository.findById(workspaceId, releaseId)
                .orElseThrow(() -> new ReleaseNotFoundException(releaseId));
        validator.validate(release.manifest());
        return release;
    }

    @Override
    @Transactional
    public ReleaseBundle create(UUID workspaceId, UUID applicationId, CreateReleaseCommand command) {
        AiApplication application = applications.get(workspaceId, applicationId);
        JsonNode manifest = telemetry.observe(application.runtimeMode(), () -> {
            JsonNode candidate = manifestFor(application);
            validator.validate(candidate);
            return candidate;
        });
        return repository.insert(
                application,
                requireVersion(command),
                digester.digest(manifest),
                manifest,
                ReleasePurpose.PRODUCTION,
                null);
    }

    @Override
    @Transactional
    public ReleaseBundle createPreview(UUID workspaceId, UUID applicationId) {
        AiApplication application = applications.get(workspaceId, applicationId);
        JsonNode manifest = telemetry.observe(application.runtimeMode(), () -> {
            JsonNode candidate = manifestFor(application);
            validator.validate(candidate);
            return candidate;
        });
        String version = "0.0.0-preview-" + UUID.randomUUID().toString().substring(0, 12);
        return repository.insert(
                application,
                version,
                digester.digest(manifest),
                manifest,
                ReleasePurpose.PREVIEW,
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(24));
    }

    private JsonNode manifestFor(AiApplication application) {
        ModelRoute route = requireRoute(application);
        String promptReference = requirePromptReference(application);
        if (application.runtimeMode() != RuntimeMode.RAG) {
            return legacyChatManifest(route, promptReference);
        }
        return ragManifest(application, route, promptReference);
    }

    private ObjectNode legacyChatManifest(
            ModelRoute route, String promptReference) {
        ObjectNode manifest = json.createObjectNode();
        manifest.put("schemaVersion", "1.0");
        manifest.put("modelRouteVersion", route.reference());
        manifest.put("promptVersion", promptReference);
        manifest.put("outputSchemaVersion", "none@1");
        manifest.putArray("knowledgeIndexVersions");
        manifest.putArray("capabilityVersions");
        manifest.putArray("policyVersions");
        manifest.put("memoryPolicyVersion", "none@1");
        manifest.put("evaluationReportVersion", "not-evaluated@1");
        manifest.putObject("runtimeParameters")
                .put("configurationSource", "application-draft");
        return manifest;
    }

    private ObjectNode ragManifest(
            AiApplication application, ModelRoute route, String promptReference) {
        ApplicationKnowledgeBindingSet selection =
                applications.getDraftKnowledgeBindings(application.workspaceId(), application.id());
        if (selection.applicationVersion() != application.version()
                || !selection.applicationId().equals(application.id())
                || selection.bindings().isEmpty()
                || selection.bindings().size() > 16) {
            throw bindingProblem();
        }

        ObjectNode manifest = json.createObjectNode();
        manifest.put("schemaVersion", "1.1");
        manifest.put("runtimeMode", "RAG");
        manifest.put("modelRouteVersion", route.reference());
        manifest.put("promptVersion", promptReference);
        manifest.put("outputSchemaVersion", "grounded-answer@1.0.0");
        ArrayNode knowledgeBindings = manifest.putArray("knowledgeBindings");
        Set<String> pinnedPolicies = new LinkedHashSet<>();
        for (int expectedOrder = 0; expectedOrder < selection.bindings().size(); expectedOrder++) {
            ApplicationKnowledgeBinding binding = selection.bindings().get(expectedOrder);
            if (binding.bindingOrder() != expectedOrder) {
                throw bindingProblem();
            }
            ResolvedBinding resolved = resolveBinding(application, binding);
            ObjectNode pin = knowledgeBindings.addObject();
            pin.put("indexVersion", resolved.index().reference());
            pin.put("retrievalPolicyVersion", resolved.policy().reference());
            pinnedPolicies.add(resolved.policy().reference());
        }
        manifest.putArray("capabilityVersions");
        ArrayNode policies = manifest.putArray("policyVersions");
        pinnedPolicies.forEach(policies::add);
        manifest.put("memoryPolicyVersion", "none@1");
        manifest.put("evaluationReportVersion", "not-evaluated@1");
        ObjectNode runtime = manifest.putObject("runtimeParameters");
        runtime.put("temperature", route.temperature());
        runtime.put("maxOutputTokens", route.maxOutputTokens());
        return manifest;
    }

    private ResolvedBinding resolveBinding(
            AiApplication application, ApplicationKnowledgeBinding binding) {
        try {
            KnowledgeIndexVersion index =
                    indexVersions.get(application.workspaceId(), binding.indexVersionId());
            RetrievalPolicyVersion policy =
                    policyVersions.get(application.workspaceId(), binding.retrievalPolicyVersionId());
            boolean exactScope = application.tenantId().equals(index.tenantId())
                    && application.workspaceId().equals(index.workspaceId())
                    && application.tenantId().equals(policy.tenantId())
                    && application.workspaceId().equals(policy.workspaceId());
            if (!exactScope
                    || index.status() != KnowledgeIndexVersion.Status.READY
                    || !policyVersions.supportsExecution(policy)) {
                throw bindingProblem();
            }
            return new ResolvedBinding(index, policy);
        } catch (KnowledgeException exception) {
            throw bindingProblem();
        }
    }

    private ModelRoute requireRoute(AiApplication application) {
        if (application.draftModelRouteId() == null) {
            throw manifestProblem();
        }
        return capabilities.listRoutes(application.workspaceId()).stream()
                .filter(route -> route.id().equals(application.draftModelRouteId()))
                .filter(route -> application.tenantId().equals(route.tenantId()))
                .findFirst()
                .orElseThrow(DefaultReleaseCatalog::manifestProblem);
    }

    private String requirePromptReference(AiApplication application) {
        if (application.draftPromptVersionId() == null) {
            throw manifestProblem();
        }
        try {
            return capabilities.promptVersionReference(
                    application.workspaceId(), application.draftPromptVersionId());
        } catch (IllegalArgumentException exception) {
            throw manifestProblem();
        }
    }

    private static String requireVersion(CreateReleaseCommand command) {
        if (command == null
                || command.version() == null
                || !command.version().matches(
                        "^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?$")) {
            throw manifestProblem();
        }
        return command.version();
    }

    private static ReleaseException manifestProblem() {
        return problem(
                "APVERO_RELEASE_MANIFEST_INVALID",
                ReleaseException.Category.BAD_REQUEST);
    }

    private static ReleaseException bindingProblem() {
        return problem(
                "APVERO_RELEASE_KNOWLEDGE_BINDING_INVALID",
                ReleaseException.Category.CONFLICT);
    }

    private static ReleaseException problem(
            String code, ReleaseException.Category category) {
        return new ReleaseException(code, category);
    }

    private record ResolvedBinding(
            KnowledgeIndexVersion index, RetrievalPolicyVersion policy) {}
}
