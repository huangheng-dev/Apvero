package io.apvero.platform.runtime;

import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RunRetrievalExecution(
        UUID retrievalId,
        int sequence,
        KnowledgeRetrievalResult.Status status,
        UUID indexVersionId,
        String indexVersionReference,
        UUID retrievalPolicyVersionId,
        String retrievalPolicyVersionReference,
        String queryDigest,
        List<RunRetrievalHit> hits,
        long latencyMs,
        long retentionDecisionVersion,
        OffsetDateTime createdAt) {
    public RunRetrievalExecution {
        hits = List.copyOf(hits);
    }
}
