package io.apvero.platform.runtime;

import java.util.List;
import java.util.UUID;

public interface RunCitationCatalog {
    List<RunCitation> list(UUID workspaceId, UUID runId);
}
