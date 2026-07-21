package io.apvero.platform.capability;

import java.util.List;
import java.util.UUID;

public interface ModelRouteReadinessCatalog {
    List<ModelRouteReadiness> list(UUID workspaceId);
}
