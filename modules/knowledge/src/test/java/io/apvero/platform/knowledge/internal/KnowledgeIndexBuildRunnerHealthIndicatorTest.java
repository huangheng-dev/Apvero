package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildOperations.ScanOutcome;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildOperations.Snapshot;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildRunner.Lifecycle;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class KnowledgeIndexBuildRunnerHealthIndicatorTest {
    private final KnowledgeAvailability availability = mock(KnowledgeAvailability.class);
    private final KnowledgeIndexBuildRunnerProperties properties =
            mock(KnowledgeIndexBuildRunnerProperties.class);
    private final KnowledgeIndexBuildRunner runner = mock(KnowledgeIndexBuildRunner.class);
    private final KnowledgeIndexBuildOperations operations =
            mock(KnowledgeIndexBuildOperations.class);
    private final KnowledgeIndexBuildRunnerHealthIndicator indicator =
            new KnowledgeIndexBuildRunnerHealthIndicator(
                    availability, properties, runner, operations);

    @Test
    void intentionalDisablementIsUpWithBoundedDetails() {
        when(availability.isEnabled()).thenReturn(false);
        when(properties.enabled()).thenReturn(false);
        when(properties.pollInterval()).thenReturn(Duration.ofSeconds(1));
        when(runner.lifecycle()).thenReturn(Lifecycle.DISABLED);
        when(operations.snapshot()).thenReturn(snapshot(
                ScanOutcome.DISABLED, 0, null, null));
        when(operations.snapshotAge()).thenReturn(Duration.ZERO);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("featureEnabled", false)
                .containsEntry("runnerEnabled", false)
                .containsEntry("accepting", false)
                .containsEntry("lifecycle", "disabled")
                .containsEntry("oldestEligibleBuildAgeSeconds", "unknown")
                .containsEntry("reconciliationCount", "unknown")
                .containsEntry("lastScanOutcome", "disabled");
    }

    @Test
    void currentAcceptingSnapshotIsUpAndReconciliationIsOnlyAnActionSignal() {
        enabled();
        when(runner.lifecycle()).thenReturn(Lifecycle.ACCEPTING);
        when(runner.inFlight()).thenReturn(2);
        when(operations.snapshot()).thenReturn(snapshot(
                ScanOutcome.SUCCESS, 0, 7L, 4L));
        when(operations.snapshotAge()).thenReturn(Duration.ofSeconds(1));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("accepting", true)
                .containsEntry("inFlight", 2)
                .containsEntry("oldestEligibleBuildAgeSeconds", 7L)
                .containsEntry("reconciliationCount", 4L);
    }

    @Test
    void repeatedFailureOrStaleSnapshotIsDownButControlledDrainIsUp() {
        enabled();
        when(runner.lifecycle()).thenReturn(Lifecycle.ACCEPTING);
        when(operations.snapshot()).thenReturn(snapshot(
                ScanOutcome.FAILED, 2, null, null));
        when(operations.snapshotAge()).thenReturn(Duration.ofSeconds(1));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        when(operations.snapshot()).thenReturn(snapshot(
                ScanOutcome.SUCCESS, 0, null, 0L));
        when(operations.snapshotAge()).thenReturn(Duration.ofSeconds(4));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        when(runner.lifecycle()).thenReturn(Lifecycle.DRAINING);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    private void enabled() {
        when(availability.isEnabled()).thenReturn(true);
        when(properties.enabled()).thenReturn(true);
        when(properties.pollInterval()).thenReturn(Duration.ofSeconds(1));
    }

    private static Snapshot snapshot(
            ScanOutcome outcome,
            int failures,
            Long oldestAge,
            Long reconciliationCount) {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new Snapshot(
                now,
                now,
                outcome == ScanOutcome.SUCCESS ? now : null,
                outcome,
                failures,
                oldestAge,
                reconciliationCount);
    }
}
