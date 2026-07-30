package io.apvero.platform.knowledge.internal;

import io.apvero.platform.governance.BudgetExceededException;
import io.apvero.platform.governance.RateLimitExceededException;
import io.apvero.platform.knowledge.KnowledgeDisabledException;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeRetrievalHit;
import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeRetrievalTelemetry {
    private final MeterRegistry meters;

    KnowledgeRetrievalTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    void succeeded(
            KnowledgeRetrievalResult.Status status,
            long elapsedNanos,
            long providerLatencyMillis,
            int rankedHitCount,
            List<KnowledgeRetrievalHit> returnedHits) {
        OutcomeTag outcome = OutcomeTag.from(status);
        recordRequest(outcome, FailureFamily.NONE, elapsedNanos);
        Timer.builder("apvero.knowledge.retrieval.provider.latency")
                .register(meters)
                .record(Duration.ofMillis(Math.max(0, providerLatencyMillis)));
        DistributionSummary.builder("apvero.knowledge.retrieval.hits")
                .tag("kind", HitKind.RANKED.token())
                .tag("outcome", outcome.token())
                .register(meters)
                .record(Math.max(0, rankedHitCount));
        DistributionSummary.builder("apvero.knowledge.retrieval.hits")
                .tag("kind", HitKind.RETURNED.token())
                .tag("outcome", outcome.token())
                .register(meters)
                .record(returnedHits.size());
        for (KnowledgeRetrievalHit hit : returnedHits) {
            Counter.builder("apvero.knowledge.retrieval.score")
                    .tag("bucket", ScoreBucket.from(hit.score()).token())
                    .register(meters)
                    .increment();
        }
    }

    void failed(RuntimeException failure, long elapsedNanos) {
        recordRequest(OutcomeTag.FAILED, FailureFamily.from(failure), elapsedNanos);
    }

    private void recordRequest(
            OutcomeTag outcome, FailureFamily failureFamily, long elapsedNanos) {
        Counter.builder("apvero.knowledge.retrieval.request")
                .tag("outcome", outcome.token())
                .tag("failure_family", failureFamily.token())
                .register(meters)
                .increment();
        Timer.builder("apvero.knowledge.retrieval.latency")
                .tag("outcome", outcome.token())
                .tag("failure_family", failureFamily.token())
                .register(meters)
                .record(Duration.ofNanos(Math.max(0, elapsedNanos)));
    }

    enum OutcomeTag {
        MATCHES,
        NO_EVIDENCE,
        FAILED;

        static OutcomeTag from(KnowledgeRetrievalResult.Status status) {
            return valueOf(status.name());
        }

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum HitKind {
        RANKED,
        RETURNED;

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum FailureFamily {
        NONE,
        DISABLED,
        ADMISSION,
        NOT_FOUND,
        VALIDATION,
        PROVIDER,
        RECONCILIATION,
        SETTLEMENT,
        CONFLICT,
        INTERNAL;

        static FailureFamily from(RuntimeException failure) {
            if (failure instanceof KnowledgeDisabledException) {
                return DISABLED;
            }
            if (failure instanceof BudgetExceededException
                    || failure instanceof RateLimitExceededException) {
                return ADMISSION;
            }
            if (!(failure instanceof KnowledgeException knowledge)) {
                return failure instanceof IllegalArgumentException ? VALIDATION : INTERNAL;
            }
            String code = knowledge.code();
            if (knowledge.category() == KnowledgeException.Category.NOT_FOUND) {
                return NOT_FOUND;
            }
            if (code.contains("SETTLEMENT")) {
                return SETTLEMENT;
            }
            if (code.contains("AMBIGUOUS") || code.contains("RECONCILIATION")) {
                return RECONCILIATION;
            }
            if (code.contains("PROVIDER")) {
                return PROVIDER;
            }
            if (code.contains("INVALID")
                    || code.contains("UNSUPPORTED")
                    || code.contains("LIMIT")) {
                return VALIDATION;
            }
            return knowledge.category() == KnowledgeException.Category.CONFLICT
                    ? CONFLICT
                    : INTERNAL;
        }

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum ScoreBucket {
        ZERO_TO_24("0_24"),
        TWENTY_FIVE_TO_49("25_49"),
        FIFTY_TO_74("50_74"),
        SEVENTY_FIVE_TO_89("75_89"),
        NINETY_TO_100("90_100");

        private final String token;

        ScoreBucket(String token) {
            this.token = token;
        }

        static ScoreBucket from(BigDecimal score) {
            if (score.compareTo(new BigDecimal("0.25")) < 0) {
                return ZERO_TO_24;
            }
            if (score.compareTo(new BigDecimal("0.50")) < 0) {
                return TWENTY_FIVE_TO_49;
            }
            if (score.compareTo(new BigDecimal("0.75")) < 0) {
                return FIFTY_TO_74;
            }
            if (score.compareTo(new BigDecimal("0.90")) < 0) {
                return SEVENTY_FIVE_TO_89;
            }
            return NINETY_TO_100;
        }

        String token() {
            return token;
        }
    }
}
