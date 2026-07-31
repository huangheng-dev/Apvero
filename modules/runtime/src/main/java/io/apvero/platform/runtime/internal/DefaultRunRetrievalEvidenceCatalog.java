package io.apvero.platform.runtime.internal;

import io.apvero.platform.runtime.RecordRetrievalEvidenceCommand;
import io.apvero.platform.runtime.RunRetrievalEvidence;
import io.apvero.platform.runtime.RunRetrievalEvidenceCatalog;
import io.apvero.platform.runtime.RunRetrievalExecution;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultRunRetrievalEvidenceCatalog implements RunRetrievalEvidenceCatalog {
    private final RunRetrievalEvidenceRepository repository;

    public DefaultRunRetrievalEvidenceCatalog(RunRetrievalEvidenceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public RunRetrievalExecution record(
            UUID workspaceId,
            UUID runId,
            RecordRetrievalEvidenceCommand command) {
        if (workspaceId == null || runId == null || command == null) {
            throw new IllegalArgumentException("APVERO_RUNTIME_RETRIEVAL_EVIDENCE_INVALID");
        }
        return repository.insert(workspaceId, runId, command);
    }

    @Override
    public RunRetrievalEvidence get(UUID workspaceId, UUID runId) {
        if (workspaceId == null || runId == null) {
            throw new IllegalArgumentException("APVERO_RUNTIME_RETRIEVAL_EVIDENCE_INVALID");
        }
        return repository.find(workspaceId, runId);
    }
}
