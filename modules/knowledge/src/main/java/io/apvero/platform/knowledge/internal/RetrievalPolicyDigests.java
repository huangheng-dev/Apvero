package io.apvero.platform.knowledge.internal;

import java.math.BigDecimal;

final class RetrievalPolicyDigests {
    private RetrievalPolicyDigests() {}

    static String canonical(
            String retrievalAlgorithmVersion,
            String tokenEstimatorVersion,
            long retentionPolicyVersion,
            int topK,
            int maxContextTokens,
            BigDecimal minimumScore,
            String overlapHandling,
            String emptyEvidenceBehavior) {
        KnowledgeCanonicalDigests.DigestBuilder digest =
                KnowledgeCanonicalDigests.builder("apvero-retrieval-policy-v1");
        digest.addString(retrievalAlgorithmVersion);
        digest.addString(tokenEstimatorVersion);
        digest.addString(Long.toString(retentionPolicyVersion));
        digest.addInt(topK);
        digest.addInt(maxContextTokens);
        digest.addString(minimumScore.stripTrailingZeros().toPlainString());
        digest.addString(overlapHandling);
        digest.addString(emptyEvidenceBehavior);
        return digest.finish();
    }
}
