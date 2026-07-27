package io.apvero.platform.knowledge.internal;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeIndexBuildBackoffPolicy {
    private final KnowledgeIndexBuildRunnerProperties properties;

    KnowledgeIndexBuildBackoffPolicy(KnowledgeIndexBuildRunnerProperties properties) {
        this.properties = properties;
    }

    Duration delay(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_INDEX_BUILD_ATTEMPT_INVALID");
        }
        long baseMillis = properties.backoffBase().toMillis();
        long maximumMillis = properties.backoffMaximum().toMillis();
        int exponent = Math.min(62, attemptCount - 1);
        long multiplier = 1L << exponent;
        long exponential = baseMillis > maximumMillis / multiplier
                ? maximumMillis
                : baseMillis * multiplier;
        return Duration.ofMillis(Math.min(maximumMillis, exponential));
    }
}
