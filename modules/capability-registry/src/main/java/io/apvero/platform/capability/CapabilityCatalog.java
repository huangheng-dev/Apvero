package io.apvero.platform.capability;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CapabilityCatalog {
    List<ModelProvider> listProviders(UUID workspaceId);
    ModelProvider createProvider(UUID workspaceId, String name, String providerType, String baseUrl, UUID secretReferenceId);
    List<ModelDefinition> listModels(UUID workspaceId);
    ModelDefinition createModel(UUID workspaceId, UUID providerId, String modelKey, String name, List<String> capabilities,
            long inputCostMicrosPerMillion, long outputCostMicrosPerMillion);
    List<ModelRoute> listRoutes(UUID workspaceId);
    ModelRoute createRoute(UUID workspaceId, String name, UUID modelId, int timeoutMs, int maxOutputTokens, BigDecimal temperature);
    List<PromptAsset> listPrompts(UUID workspaceId);
    PromptAsset createPrompt(UUID workspaceId, String slug, String name, String description);
    List<PromptVersion> listPromptVersions(UUID workspaceId, UUID promptAssetId);
    PromptVersion createPromptVersion(UUID workspaceId, UUID promptAssetId, String systemPrompt, List<String> variables);
    String modelRouteReference(UUID workspaceId, UUID modelRouteId);
    String promptVersionReference(UUID workspaceId, UUID promptVersionId);
    RuntimeConfiguration resolve(UUID workspaceId, String modelRouteReference, String promptVersionReference);
}
