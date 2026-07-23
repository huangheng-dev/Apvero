package io.apvero.platform.identity;

import java.util.List;
import java.util.UUID;

public interface WorkspaceScopeCatalog {
    WorkspaceScope require(UUID workspaceId);

    List<WorkspaceScope> listForBackgroundProcessing();
}
