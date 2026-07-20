package io.apvero.platform.identity;

import java.util.UUID;

public interface WorkspaceScopeCatalog {
    WorkspaceScope require(UUID workspaceId);
}
