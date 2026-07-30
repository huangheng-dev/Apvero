package io.apvero.platform.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record KnowledgeRetrievalResult(
        Status status,
        UUID indexVersionId,
        UUID retrievalPolicyVersionId,
        String queryDigest,
        List<KnowledgeRetrievalHit> hits,
        long latencyMs) {
    private static final Pattern DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");

    public KnowledgeRetrievalResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(indexVersionId, "indexVersionId");
        Objects.requireNonNull(retrievalPolicyVersionId, "retrievalPolicyVersionId");
        Objects.requireNonNull(queryDigest, "queryDigest");
        Objects.requireNonNull(hits, "hits");
        hits = List.copyOf(hits);
        if (!DIGEST.matcher(queryDigest).matches()
                || hits.size() > 100
                || latencyMs < 0
                || (status == Status.MATCHES && hits.isEmpty())
                || (status == Status.NO_EVIDENCE && !hits.isEmpty())) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_RETRIEVAL_RESULT_INVALID");
        }
    }

    public enum Status {
        MATCHES,
        NO_EVIDENCE
    }
}
