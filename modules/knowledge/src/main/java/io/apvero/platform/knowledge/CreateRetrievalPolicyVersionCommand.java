package io.apvero.platform.knowledge;

import java.math.BigDecimal;

public record CreateRetrievalPolicyVersionCommand(
        String slug,
        String version,
        Integer topK,
        Integer maxContextTokens,
        BigDecimal minimumScore,
        RetrievalPolicyOverlapHandling overlapHandling) {}
