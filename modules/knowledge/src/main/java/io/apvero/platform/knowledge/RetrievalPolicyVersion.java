package io.apvero.platform.knowledge;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RetrievalPolicyVersion(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        String slug,
        String version,
        String reference,
        int topK,
        int maxContextTokens,
        BigDecimal minimumScore,
        RetrievalPolicyOverlapHandling overlapHandling,
        String retrievalAlgorithmVersion,
        String tokenEstimatorVersion,
        long retentionPolicyVersionAtPublish,
        String policyDigest,
        String emptyEvidenceBehavior,
        OffsetDateTime createdAt) {}
