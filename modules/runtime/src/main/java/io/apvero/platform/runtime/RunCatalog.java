package io.apvero.platform.runtime;

import java.util.List;
import java.util.UUID;

public interface RunCatalog {
    List<RunRecord> list(UUID workspaceId);

    RunRecord execute(UUID workspaceId, UUID applicationId, ExecuteRunCommand command);

    UsageSummary usage(UUID workspaceId);
}
