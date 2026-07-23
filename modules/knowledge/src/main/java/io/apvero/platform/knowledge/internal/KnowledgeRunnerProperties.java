package io.apvero.platform.knowledge.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("apvero.knowledge.runner")
record KnowledgeRunnerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("4") int claimBatch,
        @DefaultValue("4") int concurrency,
        @DefaultValue("60s") Duration leaseDuration,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("2s") Duration backoffBase,
        @DefaultValue("5m") Duration backoffMaximum,
        @DefaultValue("30s") Duration gracefulDrain) {

    KnowledgeRunnerProperties {
        if (claimBatch < 1 || claimBatch > 100 || concurrency < 1 || concurrency > 64) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_RUNNER_CAPACITY_INVALID");
        }
        requirePositive(leaseDuration);
        requirePositive(pollInterval);
        requirePositive(backoffBase);
        requirePositive(backoffMaximum);
        requirePositive(gracefulDrain);
        if (backoffMaximum.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_RUNNER_TIMING_INVALID");
        }
    }

    private static void requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_RUNNER_TIMING_INVALID");
        }
    }
}
