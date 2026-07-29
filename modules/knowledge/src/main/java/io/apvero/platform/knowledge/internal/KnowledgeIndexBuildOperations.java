package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildOperationalSlice;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeIndexBuildOperations {
    private final KnowledgeIndexPersistenceRepository repository;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot;

    @Autowired
    KnowledgeIndexBuildOperations(KnowledgeIndexPersistenceRepository repository) {
        this(repository, Clock.systemUTC());
    }

    KnowledgeIndexBuildOperations(
            KnowledgeIndexPersistenceRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
        Instant now = clock.instant();
        this.snapshot = new AtomicReference<>(
                new Snapshot(now, now, null, ScanOutcome.NOT_SCANNED, 0, null, null));
    }

    OperationalAggregate scan(List<WorkspaceScope> scopes) {
        Objects.requireNonNull(scopes, "APVERO_KNOWLEDGE_INDEX_BUILD_SCOPES_REQUIRED");
        Long oldestEligibleAgeSeconds = null;
        long reconciliationCount = 0;
        for (WorkspaceScope scope : scopes) {
            BuildOperationalSlice slice = repository.readBuildOperationalSlice(scope);
            if (slice.oldestEligibleAgeSeconds() != null) {
                oldestEligibleAgeSeconds = oldestEligibleAgeSeconds == null
                        ? slice.oldestEligibleAgeSeconds()
                        : Math.max(
                                oldestEligibleAgeSeconds,
                                slice.oldestEligibleAgeSeconds());
            }
            reconciliationCount = saturatedAdd(
                    reconciliationCount, slice.reconciliationCount());
        }
        return new OperationalAggregate(
                oldestEligibleAgeSeconds, reconciliationCount);
    }

    void succeeded(OperationalAggregate aggregate) {
        Objects.requireNonNull(
                aggregate, "APVERO_KNOWLEDGE_INDEX_BUILD_AGGREGATE_REQUIRED");
        Instant now = clock.instant();
        Snapshot previous = snapshot.get();
        snapshot.set(new Snapshot(
                previous.initializedAt(),
                now,
                now,
                ScanOutcome.SUCCESS,
                0,
                aggregate.oldestEligibleAgeSeconds(),
                aggregate.reconciliationCount()));
    }

    void failed() {
        Instant now = clock.instant();
        snapshot.updateAndGet(previous -> new Snapshot(
                previous.initializedAt(),
                now,
                previous.lastSuccessfulAt(),
                ScanOutcome.FAILED,
                saturatedIncrement(previous.consecutiveFailures()),
                null,
                null));
    }

    void disabled() {
        Instant now = clock.instant();
        snapshot.updateAndGet(previous -> new Snapshot(
                previous.initializedAt(),
                now,
                previous.lastSuccessfulAt(),
                ScanOutcome.DISABLED,
                0,
                null,
                null));
    }

    Snapshot snapshot() {
        return snapshot.get();
    }

    Duration snapshotAge() {
        Snapshot current = snapshot.get();
        Instant reference = current.lastSuccessfulAt() == null
                ? current.initializedAt()
                : current.lastSuccessfulAt();
        Duration age = Duration.between(reference, clock.instant());
        return age.isNegative() ? Duration.ZERO : age;
    }

    private static long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static int saturatedIncrement(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    enum ScanOutcome {
        NOT_SCANNED,
        SUCCESS,
        FAILED,
        DISABLED;

        String token() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    record Snapshot(
            Instant initializedAt,
            Instant scannedAt,
            Instant lastSuccessfulAt,
            ScanOutcome lastScanOutcome,
            int consecutiveFailures,
            Long oldestEligibleAgeSeconds,
            Long reconciliationCount) {
        Snapshot {
            Objects.requireNonNull(initializedAt);
            Objects.requireNonNull(scannedAt);
            Objects.requireNonNull(lastScanOutcome);
            if (consecutiveFailures < 0
                    || (oldestEligibleAgeSeconds != null && oldestEligibleAgeSeconds < 0)
                    || (reconciliationCount != null && reconciliationCount < 0)) {
                throw new IllegalArgumentException(
                        "APVERO_KNOWLEDGE_INDEX_BUILD_SNAPSHOT_INVALID");
            }
            if (lastScanOutcome != ScanOutcome.SUCCESS
                    && (oldestEligibleAgeSeconds != null || reconciliationCount != null)) {
                throw new IllegalArgumentException(
                        "APVERO_KNOWLEDGE_INDEX_BUILD_SNAPSHOT_INVALID");
            }
        }
    }

    record OperationalAggregate(
            Long oldestEligibleAgeSeconds,
            long reconciliationCount) {
        OperationalAggregate {
            if ((oldestEligibleAgeSeconds != null && oldestEligibleAgeSeconds < 0)
                    || reconciliationCount < 0) {
                throw new IllegalArgumentException(
                        "APVERO_KNOWLEDGE_INDEX_BUILD_AGGREGATE_INVALID");
            }
        }
    }
}
