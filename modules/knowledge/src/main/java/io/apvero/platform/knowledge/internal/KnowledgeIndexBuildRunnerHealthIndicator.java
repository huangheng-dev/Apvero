package io.apvero.platform.knowledge.internal;

import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildOperations.ScanOutcome;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildOperations.Snapshot;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildRunner.Lifecycle;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("knowledgeIndexBuildRunner")
final class KnowledgeIndexBuildRunnerHealthIndicator implements HealthIndicator {
    private static final int FAILURE_THRESHOLD = 2;
    private static final int STALE_INTERVAL_MULTIPLIER = 3;

    private final KnowledgeAvailability availability;
    private final KnowledgeIndexBuildRunnerProperties properties;
    private final KnowledgeIndexBuildRunner runner;
    private final KnowledgeIndexBuildOperations operations;

    KnowledgeIndexBuildRunnerHealthIndicator(
            KnowledgeAvailability availability,
            KnowledgeIndexBuildRunnerProperties properties,
            KnowledgeIndexBuildRunner runner,
            KnowledgeIndexBuildOperations operations) {
        this.availability = availability;
        this.properties = properties;
        this.runner = runner;
        this.operations = operations;
    }

    @Override
    public Health health() {
        boolean featureEnabled = availability.isEnabled();
        boolean runnerEnabled = properties.enabled();
        Lifecycle lifecycle = runner.lifecycle();
        Snapshot snapshot = operations.snapshot();
        Duration snapshotAge = operations.snapshotAge();
        long snapshotAgeSeconds = snapshotAge.toSeconds();

        Health.Builder builder = unhealthy(
                        featureEnabled,
                        runnerEnabled,
                        lifecycle,
                        snapshot,
                        snapshotAge)
                ? Health.down()
                : Health.up();
        return builder.withDetail("featureEnabled", featureEnabled)
                .withDetail("runnerEnabled", runnerEnabled)
                .withDetail("accepting", lifecycle == Lifecycle.ACCEPTING)
                .withDetail("lifecycle", lifecycle.name().toLowerCase(Locale.ROOT))
                .withDetail("inFlight", runner.inFlight())
                .withDetail(
                        "oldestEligibleBuildAgeSeconds",
                        valueOrUnknown(snapshot.oldestEligibleAgeSeconds()))
                .withDetail(
                        "reconciliationCount",
                        valueOrUnknown(snapshot.reconciliationCount()))
                .withDetail("lastScanOutcome", snapshot.lastScanOutcome().token())
                .withDetail("snapshotAgeSeconds", snapshotAgeSeconds)
                .build();
    }

    private boolean unhealthy(
            boolean featureEnabled,
            boolean runnerEnabled,
            Lifecycle lifecycle,
            Snapshot snapshot,
            Duration snapshotAge) {
        if (!featureEnabled || !runnerEnabled || lifecycle == Lifecycle.DRAINING) {
            return false;
        }
        if (lifecycle == Lifecycle.STOPPED) {
            return true;
        }
        if (lifecycle == Lifecycle.DISABLED
                && snapshot.lastScanOutcome() != ScanOutcome.NOT_SCANNED) {
            return true;
        }
        if (snapshot.consecutiveFailures() >= FAILURE_THRESHOLD) {
            return true;
        }
        Duration staleThreshold =
                properties.pollInterval().multipliedBy(STALE_INTERVAL_MULTIPLIER);
        return snapshotAge.compareTo(staleThreshold) > 0;
    }

    private static Object valueOrUnknown(Long value) {
        return value == null ? "unknown" : value;
    }
}
