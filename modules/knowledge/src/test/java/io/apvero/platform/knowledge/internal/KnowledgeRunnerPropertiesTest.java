package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeRunnerPropertiesTest {
    @Test
    void backoffIsDeterministicBoundedAndIncreases() {
        KnowledgeRunnerProperties properties = properties(Duration.ofSeconds(2), Duration.ofMinutes(5));
        KnowledgeBackoffPolicy policy = new KnowledgeBackoffPolicy(properties);
        UUID jobId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        Duration first = policy.delay(jobId, 1);
        Duration second = policy.delay(jobId, 2);
        Duration capped = policy.delay(jobId, 30);

        assertThat(policy.delay(jobId, 1)).isEqualTo(first);
        assertThat(second).isGreaterThan(first);
        assertThat(capped).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsUnsafeCapacityAndTiming() {
        assertThatThrownBy(() -> new KnowledgeRunnerProperties(
                        true, 0, 4, Duration.ofSeconds(60), Duration.ofSeconds(1),
                        Duration.ofSeconds(2), Duration.ofMinutes(5), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_RUNNER_CAPACITY_INVALID");
        assertThatThrownBy(() -> new KnowledgeRunnerProperties(
                        true, 4, 4, Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_RUNNER_TIMING_INVALID");
    }

    @Test
    void rejectsLeaseShorterThanBoundedExternalIo() {
        KnowledgeRunnerProperties runner = new KnowledgeRunnerProperties(
                true, 4, 4, Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ofMinutes(5), Duration.ofSeconds(30));
        KnowledgeProperties knowledge = new KnowledgeProperties(
                false, URI.create("http://ai-worker:8090"), Duration.ofSeconds(15), 20_971_520,
                5_242_880, 5_242_880, 2_048, 20_971_520);
        WebCaptureProperties web = new WebCaptureProperties(
                5, 65_536, Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(() -> new KnowledgeRunnerLeaseGuard(knowledge, runner, web))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_RUNNER_LEASE_TOO_SHORT");
    }

    private static KnowledgeRunnerProperties properties(Duration base, Duration maximum) {
        return new KnowledgeRunnerProperties(
                true, 4, 4, Duration.ofSeconds(60), Duration.ofSeconds(1),
                base, maximum, Duration.ofSeconds(30));
    }
}
