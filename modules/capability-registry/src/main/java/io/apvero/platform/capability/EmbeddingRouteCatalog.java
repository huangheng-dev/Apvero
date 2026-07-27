package io.apvero.platform.capability;

import java.util.Optional;
import java.util.UUID;

public interface EmbeddingRouteCatalog {
    Optional<EmbeddingRouteSnapshot> findEmbeddingRoute(UUID workspaceId, UUID routeId);
}
