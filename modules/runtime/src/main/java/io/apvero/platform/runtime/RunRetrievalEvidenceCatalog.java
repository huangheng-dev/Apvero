package io.apvero.platform.runtime;

import java.util.UUID;

public interface RunRetrievalEvidenceCatalog {
    RunRetrievalExecution record(
            UUID workspaceId,
            UUID runId,
            RecordRetrievalEvidenceCommand command);

    RunRetrievalEvidence get(UUID workspaceId, UUID runId);
}
