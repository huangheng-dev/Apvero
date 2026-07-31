package io.apvero.platform.runtime.internal;

import io.apvero.platform.capability.ExecutionRetentionDecision;
import io.apvero.platform.runtime.ProviderResult;
import io.apvero.platform.runtime.RunRecord;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroundedAnswerFinalizer {
    private final RunRetrievalEvidenceRepository evidence;
    private final RunRepository runs;
    private final GroundedAnswerValidator validator;
    private final RuntimePayloadRetention retention;

    public GroundedAnswerFinalizer(
            RunRetrievalEvidenceRepository evidence,
            RunRepository runs,
            GroundedAnswerValidator validator,
            RuntimePayloadRetention retention) {
        this.evidence = evidence;
        this.runs = runs;
        this.validator = validator;
        this.retention = retention;
    }

    @Transactional
    public RunRecord complete(
            UUID workspaceId,
            UUID runId,
            String providerId,
            ProviderResult result,
            ExecutionRetentionDecision retentionDecision,
            long latencyMs) {
        var retainedEvidence = evidence.lockForValidation(workspaceId, runId);
        var answer = validator.validate(result.output(), retainedEvidence);
        evidence.markCitationsValidated(workspaceId, runId, answer.markers());
        return runs.completeSuccess(
                workspaceId,
                runId,
                providerId,
                retention.apply(answer.output(), retentionDecision),
                result,
                latencyMs);
    }
}
