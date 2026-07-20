package io.apvero.platform.release.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.capability.CapabilityCatalog;
import io.apvero.platform.release.CreateReleaseCommand;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleaseCatalog;
import io.apvero.platform.release.ReleaseNotFoundException;
import io.apvero.platform.release.ReleasePurpose;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultReleaseCatalog implements ReleaseCatalog {
    private final ApplicationCatalog applications;
    private final ReleaseRepository repository;
    private final ReleaseManifestValidator validator;
    private final ReleaseArtifactDigester digester;
    private final CapabilityCatalog capabilities;
    private final ObjectMapper json;

    public DefaultReleaseCatalog(
            ApplicationCatalog applications,
            ReleaseRepository repository,
            ReleaseManifestValidator validator,
            ReleaseArtifactDigester digester,
            CapabilityCatalog capabilities,
            ObjectMapper json) {
        this.applications = applications;
        this.repository = repository;
        this.validator = validator;
        this.digester = digester;
        this.capabilities = capabilities;
        this.json = json;
    }

    @Override
    public List<ReleaseBundle> list(UUID workspaceId, UUID applicationId) {
        applications.get(workspaceId, applicationId);
        return repository.findAll(workspaceId, applicationId);
    }

    @Override
    public ReleaseBundle get(UUID workspaceId, UUID releaseId) {
        return repository.findById(workspaceId, releaseId)
                .orElseThrow(() -> new ReleaseNotFoundException(releaseId));
    }

    @Override
    @Transactional
    public ReleaseBundle create(UUID workspaceId, UUID applicationId, CreateReleaseCommand command) {
        AiApplication application = applications.get(workspaceId, applicationId);
        JsonNode manifest = command.manifest() == null ? manifestFor(application) : command.manifest();
        validator.validate(manifest);
        return repository.insert(application, command.version(), digester.digest(manifest), manifest,
                ReleasePurpose.PRODUCTION, null);
    }

    @Override
    @Transactional
    public ReleaseBundle createPreview(UUID workspaceId, UUID applicationId) {
        AiApplication application = applications.get(workspaceId, applicationId);
        JsonNode manifest = manifestFor(application);
        validator.validate(manifest);
        String version = "0.0.0-preview-" + UUID.randomUUID().toString().substring(0, 12);
        return repository.insert(application, version, digester.digest(manifest), manifest,
                ReleasePurpose.PREVIEW, OffsetDateTime.now(ZoneOffset.UTC).plusHours(24));
    }

    private JsonNode manifestFor(AiApplication application) {
        if (application.draftModelRouteId() == null || application.draftPromptVersionId() == null) {
            throw new IllegalArgumentException("Application draft must bind a model route and Prompt version before release.");
        }
        ObjectNode manifest = json.createObjectNode();
        manifest.put("schemaVersion", "1.0");
        manifest.put("modelRouteVersion", capabilities.modelRouteReference(application.workspaceId(), application.draftModelRouteId()));
        manifest.put("promptVersion", capabilities.promptVersionReference(application.workspaceId(), application.draftPromptVersionId()));
        manifest.put("outputSchemaVersion", "none@1");
        manifest.putArray("knowledgeIndexVersions");
        manifest.putArray("capabilityVersions");
        manifest.putArray("policyVersions");
        manifest.put("memoryPolicyVersion", "none@1");
        manifest.put("evaluationReportVersion", "not-evaluated@1");
        ObjectNode runtime = manifest.putObject("runtimeParameters");
        runtime.put("configurationSource", "application-draft");
        return manifest;
    }
}
