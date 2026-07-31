package io.apvero.platform.runtime.internal;

import io.apvero.platform.runtime.RunCitation;
import io.apvero.platform.runtime.RunCitationCatalog;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultRunCitationCatalog implements RunCitationCatalog {
    private final RunRetrievalEvidenceRepository repository;

    public DefaultRunCitationCatalog(RunRetrievalEvidenceRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<RunCitation> list(UUID workspaceId, UUID runId) {
        if (workspaceId == null || runId == null) {
            throw new IllegalArgumentException(
                    "APVERO_RUNTIME_CITATION_REQUEST_INVALID");
        }
        return repository.findValidatedCitations(workspaceId, runId);
    }
}
