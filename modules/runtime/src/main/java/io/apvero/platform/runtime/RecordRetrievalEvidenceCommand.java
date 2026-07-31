package io.apvero.platform.runtime;

import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import java.util.Objects;
import java.util.regex.Pattern;

public record RecordRetrievalEvidenceCommand(
        int sequence,
        String indexVersionReference,
        String retrievalPolicyVersionReference,
        long retentionDecisionVersion,
        boolean retainPayloads,
        boolean maskSensitiveFields,
        KnowledgeRetrievalResult result) {
    private static final Pattern REFERENCE = Pattern.compile(
            "^[a-z0-9][a-z0-9._:/-]*@[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?$");

    public RecordRetrievalEvidenceCommand {
        Objects.requireNonNull(indexVersionReference, "indexVersionReference");
        Objects.requireNonNull(retrievalPolicyVersionReference, "retrievalPolicyVersionReference");
        Objects.requireNonNull(result, "result");
        if (sequence < 0
                || sequence > 15
                || retentionDecisionVersion < 1
                || indexVersionReference.length() > 240
                || retrievalPolicyVersionReference.length() > 240
                || !REFERENCE.matcher(indexVersionReference).matches()
                || !REFERENCE.matcher(retrievalPolicyVersionReference).matches()) {
            throw new RunEvidenceException(
                    "APVERO_RUNTIME_RETRIEVAL_EVIDENCE_INVALID",
                    RunEvidenceException.Category.BAD_REQUEST);
        }
    }

    public boolean discloseContent() {
        return retainPayloads && !maskSensitiveFields;
    }
}
