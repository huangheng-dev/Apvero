package io.apvero.platform.knowledge.internal;

import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildOperations.Snapshot;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeIndexBuildTelemetry {
    private final MeterRegistry meters;
    private final AtomicBoolean gaugesBound = new AtomicBoolean();
    private IntSupplier inFlightGauge;
    private Supplier<Snapshot> snapshotGauge;

    KnowledgeIndexBuildTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    void bindGauges(
            IntSupplier inFlight,
            Supplier<Snapshot> snapshot) {
        if (!gaugesBound.compareAndSet(false, true)) {
            return;
        }
        this.inFlightGauge = inFlight;
        this.snapshotGauge = snapshot;
        Gauge.builder(
                        "apvero.knowledge.index.build.inflight",
                        this,
                        source -> source.inFlightGauge.getAsInt())
                .register(meters);
        Gauge.builder(
                        "apvero.knowledge.index.build.oldest.eligible.age",
                        this,
                        source -> numberOrUnknown(
                                source.snapshotGauge.get().oldestEligibleAgeSeconds()))
                .register(meters);
        Gauge.builder(
                        "apvero.knowledge.index.build.reconciliation",
                        this,
                        source -> numberOrUnknown(
                                source.snapshotGauge.get().reconciliationCount()))
                .register(meters);
    }

    void claimed(BuildStep step) {
        Counter.builder("apvero.knowledge.index.build.claimed")
                .tag("step", StepTag.from(step).token())
                .register(meters)
                .increment();
    }

    void queueWait(BuildStep step, long elapsedNanos) {
        Timer.builder("apvero.knowledge.index.build.queue.wait")
                .tag("step", StepTag.from(step).token())
                .register(meters)
                .record(Duration.ofNanos(Math.max(0, elapsedNanos)));
    }

    void stepDuration(
            BuildStep step,
            OutcomeTag outcome,
            ErrorCategoryTag errorCategory,
            long elapsedNanos) {
        Timer.builder("apvero.knowledge.index.build.step.duration")
                .tag("step", StepTag.from(step).token())
                .tag("outcome", outcome.token())
                .tag("error_category", errorCategory.token())
                .register(meters)
                .record(Duration.ofNanos(Math.max(0, elapsedNanos)));
    }

    void attempt(BuildStep step, int attemptCount) {
        Counter.builder("apvero.knowledge.index.build.attempt")
                .tag("step", StepTag.from(step).token())
                .tag("attempt_bucket", AttemptBucket.from(attemptCount).token())
                .register(meters)
                .increment();
    }

    void batchItems(OutcomeTag outcome, int count) {
        DistributionSummary.builder("apvero.knowledge.index.build.batch.items")
                .tag("outcome", outcome.token())
                .register(meters)
                .record(Math.max(0, count));
    }

    void batchUnits(QualityTag quality, OutcomeTag outcome, long units) {
        DistributionSummary.builder("apvero.knowledge.index.build.batch.units")
                .tag("quality", quality.token())
                .tag("outcome", outcome.token())
                .register(meters)
                .record(Math.max(0, units));
    }

    void entries(EntryKindTag kind, OutcomeTag outcome, int count) {
        DistributionSummary.builder("apvero.knowledge.index.build.entries")
                .tag("kind", kind.token())
                .tag("outcome", outcome.token())
                .register(meters)
                .record(Math.max(0, count));
    }

    void retry(BuildStep step, ErrorCategoryTag errorCategory) {
        Counter.builder("apvero.knowledge.index.build.retry")
                .tag("step", StepTag.from(step).token())
                .tag("error_category", errorCategory.token())
                .register(meters)
                .increment();
    }

    void staleLease(BuildStep step, StaleOperationTag operation) {
        Counter.builder("apvero.knowledge.index.build.stale.lease")
                .tag("step", StepTag.from(step).token())
                .tag("operation", operation.token())
                .register(meters)
                .increment();
    }

    void recovery(RecoveryAction action, OutcomeTag outcome) {
        Counter.builder("apvero.knowledge.index.build.recovery")
                .tag("action", action.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome.token())
                .register(meters)
                .increment();
    }

    void publicationValidation(
            PublicationValidationTag outcome,
            ErrorCategoryTag errorCategory) {
        Counter.builder("apvero.knowledge.index.build.publication.validation")
                .tag("outcome", outcome.token())
                .tag("error_category", errorCategory.token())
                .register(meters)
                .increment();
    }

    void publication(PublicationTag outcome) {
        Counter.builder("apvero.knowledge.index.build.publication")
                .tag("outcome", outcome.token())
                .register(meters)
                .increment();
    }

    private static double numberOrUnknown(Long value) {
        return value == null ? Double.NaN : value.doubleValue();
    }

    enum StepTag {
        EMBEDDING,
        INDEXING,
        VALIDATING;

        static StepTag from(BuildStep step) {
            return switch (step) {
                case EMBEDDING -> EMBEDDING;
                case INDEXING -> INDEXING;
                case VALIDATING -> VALIDATING;
                case COMPLETE -> throw new IllegalArgumentException(
                        "APVERO_KNOWLEDGE_INDEX_BUILD_METRIC_STEP_INVALID");
            };
        }

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum OutcomeTag {
        SUCCESS,
        RETRY,
        FAILED,
        STALE,
        RECONCILIATION,
        REPLAYED,
        REJECTED;

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum ErrorCategoryTag {
        NONE,
        VALIDATION,
        SECURITY,
        TRANSIENT,
        PERMANENT,
        INTERNAL,
        AMBIGUOUS,
        CONFLICT;

        static ErrorCategoryTag fromStored(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ignored) {
                return INTERNAL;
            }
        }

        static ErrorCategoryTag from(KnowledgeIndexBuildFailure.Category category) {
            return valueOf(category.name());
        }

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum AttemptBucket {
        ONE("1"),
        TWO("2"),
        THREE("3"),
        FOUR_PLUS("4_plus");

        private final String token;

        AttemptBucket(String token) {
            this.token = token;
        }

        static AttemptBucket from(int attemptCount) {
            return switch (attemptCount) {
                case 0, 1 -> ONE;
                case 2 -> TWO;
                case 3 -> THREE;
                default -> FOUR_PLUS;
            };
        }

        String token() {
            return token;
        }
    }

    enum QualityTag {
        ACTUAL,
        ESTIMATED;

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum EntryKindTag {
        REQUESTED,
        EMBEDDED,
        VALIDATED;

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum StaleOperationTag {
        RENEW,
        FAILURE,
        TRANSITION,
        PUBLICATION;

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum PublicationValidationTag {
        ADVANCED,
        FAILED;

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum PublicationTag {
        PUBLISHED,
        REPLAYED,
        FAILED;

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
