package io.apvero.platform.knowledge.internal;

import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeBackoffPolicy {
    private final KnowledgeRunnerProperties properties;

    KnowledgeBackoffPolicy(KnowledgeRunnerProperties properties) {
        this.properties = properties;
    }

    Duration delay(UUID jobId, int attemptCount) {
        int exponent = Math.max(0, Math.min(30, attemptCount - 1));
        long baseMillis = properties.backoffBase().toMillis();
        long maximumMillis = properties.backoffMaximum().toMillis();
        long exponential = baseMillis > (maximumMillis >> exponent)
                ? maximumMillis : Math.min(maximumMillis, baseMillis << exponent);
        long hash = jobId.getMostSignificantBits() ^ jobId.getLeastSignificantBits() ^ attemptCount;
        double jitter = 0.8d + (Math.floorMod(hash, 401L) / 1000d);
        return Duration.ofMillis(Math.max(1, Math.min(maximumMillis, Math.round(exponential * jitter))));
    }
}
