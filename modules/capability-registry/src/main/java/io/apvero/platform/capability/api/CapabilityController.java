package io.apvero.platform.capability.api;

import io.apvero.platform.capability.CapabilityCatalog;
import io.apvero.platform.capability.ModelDefinition;
import io.apvero.platform.capability.ModelProvider;
import io.apvero.platform.capability.ModelRoute;
import io.apvero.platform.capability.PromptAsset;
import io.apvero.platform.capability.PromptVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class CapabilityController {
    private final CapabilityCatalog capabilities;

    CapabilityController(CapabilityCatalog capabilities) {
        this.capabilities = capabilities;
    }

    @GetMapping("/model-providers")
    List<ModelProvider> providers(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return capabilities.listProviders(workspaceId);
    }

    @PostMapping("/model-providers")
    ResponseEntity<ModelProvider> createProvider(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @Valid @RequestBody CreateProviderRequest request) {
        ModelProvider created = capabilities.createProvider(workspaceId, request.name(), request.providerType(),
                request.baseUrl(), request.secretReferenceId());
        return ResponseEntity.created(URI.create("/api/v1/model-providers/" + created.id())).body(created);
    }

    @GetMapping("/models")
    List<ModelDefinition> models(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return capabilities.listModels(workspaceId);
    }

    @PostMapping("/models")
    ResponseEntity<ModelDefinition> createModel(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @Valid @RequestBody CreateModelRequest request) {
        ModelDefinition created = capabilities.createModel(workspaceId, request.providerId(), request.modelKey(),
                request.name(), request.capabilities(), request.inputCostMicrosPerMillion(),
                request.outputCostMicrosPerMillion());
        return ResponseEntity.created(URI.create("/api/v1/models/" + created.id())).body(created);
    }

    @GetMapping("/model-routes")
    List<ModelRoute> routes(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return capabilities.listRoutes(workspaceId);
    }

    @PostMapping("/model-routes")
    ResponseEntity<ModelRoute> createRoute(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @Valid @RequestBody CreateRouteRequest request) {
        ModelRoute created = capabilities.createRoute(workspaceId, request.name(), request.modelId(), request.timeoutMs(),
                request.maxOutputTokens(), request.temperature());
        return ResponseEntity.created(URI.create("/api/v1/model-routes/" + created.id())).body(created);
    }

    @GetMapping("/prompts")
    List<PromptAsset> prompts(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return capabilities.listPrompts(workspaceId);
    }

    @PostMapping("/prompts")
    ResponseEntity<PromptAsset> createPrompt(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @Valid @RequestBody CreatePromptRequest request) {
        PromptAsset created = capabilities.createPrompt(workspaceId, request.slug(), request.name(), request.description());
        return ResponseEntity.created(URI.create("/api/v1/prompts/" + created.id())).body(created);
    }

    @GetMapping("/prompts/{promptId}/versions")
    List<PromptVersion> promptVersions(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID promptId) {
        return capabilities.listPromptVersions(workspaceId, promptId);
    }

    @PostMapping("/prompts/{promptId}/versions")
    ResponseEntity<PromptVersion> createPromptVersion(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID promptId,
            @Valid @RequestBody CreatePromptVersionRequest request) {
        PromptVersion created = capabilities.createPromptVersion(workspaceId, promptId, request.systemPrompt(), request.variables());
        return ResponseEntity.created(URI.create("/api/v1/prompts/" + promptId + "/versions/" + created.id())).body(created);
    }

    record CreateProviderRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "^(OPENAI_COMPATIBLE|DETERMINISTIC_LOCAL)$") String providerType,
            @NotBlank @Size(max = 500) String baseUrl,
            UUID secretReferenceId) {}

    record CreateModelRequest(
            @NotNull UUID providerId,
            @NotBlank @Size(max = 200) String modelKey,
            @NotBlank @Size(max = 160) String name,
            List<String> capabilities,
            @Min(0) long inputCostMicrosPerMillion,
            @Min(0) long outputCostMicrosPerMillion) {}

    record CreateRouteRequest(
            @NotBlank @Size(max = 160) String name,
            @NotNull UUID modelId,
            @Min(1000) @Max(300000) int timeoutMs,
            @Min(1) @Max(200000) int maxOutputTokens,
            BigDecimal temperature) {}

    record CreatePromptRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 2000) String description) {}

    record CreatePromptVersionRequest(
            @NotBlank @Size(max = 50000) String systemPrompt,
            List<String> variables) {}
}
