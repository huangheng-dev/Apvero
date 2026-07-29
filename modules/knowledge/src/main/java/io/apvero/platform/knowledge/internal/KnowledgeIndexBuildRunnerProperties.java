package io.apvero.platform.knowledge.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("apvero.knowledge.index-build-runner")
record KnowledgeIndexBuildRunnerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("4") int claimBatch,
        @DefaultValue("4") int concurrency,
        @DefaultValue("60s") Duration leaseDuration,
        @DefaultValue("30s") Duration externalCallTimeout,
        @DefaultValue("10s") Duration commitMargin,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("2s") Duration backoffBase,
        @DefaultValue("5m") Duration backoffMaximum,
        @DefaultValue("30s") Duration gracefulDrain) {
    private static final Duration MAXIMUM_TIMING = Duration.ofHours(24);

    KnowledgeIndexBuildRunnerProperties {
        if (claimBatch < 1 || claimBatch > 100 || concurrency < 1 || concurrency > 64) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_CAPACITY_INVALID");
        }
        requireBoundedPositive(leaseDuration);
        requireBoundedPositive(externalCallTimeout);
        requireBoundedPositive(commitMargin);
        requireBoundedPositive(pollInterval);
        requireBoundedPositive(backoffBase);
        requireBoundedPositive(backoffMaximum);
        requireBoundedPositive(gracefulDrain);
        if (backoffMaximum.compareTo(backoffBase) < 0
                || leaseDuration.compareTo(externalCallTimeout.plus(commitMargin)) <= 0) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_TIMING_INVALID");
        }
    }

    private static void requireBoundedPositive(Duration value) {
        if (value == null
                || value.compareTo(Duration.ofMillis(1)) < 0
                || value.compareTo(MAXIMUM_TIMING) > 0) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_TIMING_INVALID");
        }
    }
}
