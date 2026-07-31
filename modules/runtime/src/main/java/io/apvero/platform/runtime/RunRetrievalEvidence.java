package io.apvero.platform.runtime;

import java.util.List;
import java.util.UUID;

public record RunRetrievalEvidence(UUID runId, List<RunRetrievalExecution> retrievals) {
    public RunRetrievalEvidence {
        retrievals = List.copyOf(retrievals);
    }
}
