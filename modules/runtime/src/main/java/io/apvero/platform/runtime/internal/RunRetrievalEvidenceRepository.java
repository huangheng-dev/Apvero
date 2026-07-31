package io.apvero.platform.runtime.internal;

import io.apvero.platform.runtime.RecordRetrievalEvidenceCommand;
import io.apvero.platform.runtime.RunRetrievalEvidence;
import io.apvero.platform.runtime.RunRetrievalExecution;
import io.apvero.platform.runtime.RunCitation;
import java.util.List;
import java.util.Set;
import java.util.UUID;

interface RunRetrievalEvidenceRepository {
    RunRetrievalExecution insert(
            UUID workspaceId,
            UUID runId,
            RecordRetrievalEvidenceCommand command);

    RunRetrievalEvidence find(UUID workspaceId, UUID runId);

    RunRetrievalEvidence lockForValidation(UUID workspaceId, UUID runId);

    void markCitationsValidated(
            UUID workspaceId, UUID runId, Set<String> markers);

    List<RunCitation> findValidatedCitations(UUID workspaceId, UUID runId);
}
