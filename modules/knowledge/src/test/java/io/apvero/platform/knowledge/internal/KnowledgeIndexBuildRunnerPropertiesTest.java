package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class KnowledgeIndexBuildRunnerPropertiesTest {
    @Test
    void backoffIsExactDeterministicBoundedAndOverflowSafe() {
        KnowledgeIndexBuildRunnerProperties properties = properties(
                4, Duration.ofSeconds(2), Duration.ofMinutes(5));
        KnowledgeIndexBuildBackoffPolicy policy = new KnowledgeIndexBuildBackoffPolicy(properties);

        assertThat(policy.delay(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delay(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delay(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.delay(63)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.delay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.delay(2)).isEqualTo(policy.delay(2));
    }

    @Test
    void rejectsInvalidAttemptCapacityAndTiming() {
        KnowledgeIndexBuildBackoffPolicy policy =
                new KnowledgeIndexBuildBackoffPolicy(properties(
                        4, Duration.ofSeconds(2), Duration.ofMinutes(5)));

        assertThatThrownBy(() -> policy.delay(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_ATTEMPT_INVALID");
        assertThatThrownBy(() -> properties(0, Duration.ofSeconds(2), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_CAPACITY_INVALID");
        assertThatThrownBy(() -> properties(4, Duration.ofMinutes(6), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_TIMING_INVALID");
        assertThatThrownBy(() -> properties(
                        4, Duration.ofNanos(1), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_TIMING_INVALID");
        assertThatThrownBy(() -> new KnowledgeIndexBuildRunnerProperties(
                        false,
                        4,
                        Duration.ofSeconds(40),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(2),
                        Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_TIMING_INVALID");
    }

    @Test
    void defaultsRemainDisabledUntilOperationsCheckpoint() {
        KnowledgeIndexBuildRunnerProperties properties =
                properties(4, Duration.ofSeconds(2), Duration.ofMinutes(5));

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.leaseDuration())
                .isGreaterThan(properties.externalCallTimeout().plus(properties.commitMargin()));
    }

    private static KnowledgeIndexBuildRunnerProperties properties(
            int claimBatch, Duration backoffBase, Duration backoffMaximum) {
        return new KnowledgeIndexBuildRunnerProperties(
                false,
                claimBatch,
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                backoffBase,
                backoffMaximum);
    }
}
