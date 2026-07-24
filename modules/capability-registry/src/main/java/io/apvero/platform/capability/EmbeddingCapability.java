package io.apvero.platform.capability;

import java.util.UUID;

public interface EmbeddingCapability {
    EmbeddingRouteSnapshot resolveEmbeddingRoute(UUID workspaceId, UUID routeId);

    EmbeddingExecutionResult embed(EmbeddingExecutionRequest request);
}
