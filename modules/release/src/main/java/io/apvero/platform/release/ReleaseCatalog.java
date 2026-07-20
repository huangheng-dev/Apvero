package io.apvero.platform.release;

import java.util.List;
import java.util.UUID;

public interface ReleaseCatalog {
    List<ReleaseBundle> list(UUID workspaceId, UUID applicationId);

    ReleaseBundle get(UUID workspaceId, UUID releaseId);

    ReleaseBundle create(UUID workspaceId, UUID applicationId, CreateReleaseCommand command);

    ReleaseBundle createPreview(UUID workspaceId, UUID applicationId);
}
