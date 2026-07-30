package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.apvero.platform.governance.BudgetExceededException;
import io.apvero.platform.knowledge.KnowledgeDisabledException;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeRetrievalHit;
import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import io.apvero.platform.knowledge.KnowledgeSource;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KnowledgeRetrievalTelemetryTest {
    private static final Map<String, Set<String>> ALLOWED_TAG_KEYS = Map.of(
            "apvero.knowledge.retrieval.request", Set.of("outcome", "failure_family"),
            "apvero.knowledge.retrieval.latency", Set.of("outcome", "failure_family"),
            "apvero.knowledge.retrieval.provider.latency", Set.of(),
            "apvero.knowledge.retrieval.hits", Set.of("kind", "outcome"),
            "apvero.knowledge.retrieval.score", Set.of("bucket"));

    @Test
    void recordsRequiredFamiliesWithOnlyBoundedNonIdentityTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        KnowledgeRetrievalTelemetry telemetry = new KnowledgeRetrievalTelemetry(meters);
        telemetry.succeeded(
                KnowledgeRetrievalResult.Status.MATCHES,
                20,
                4,
                3,
                List.of(hit("0.10"), hit("0.74"), hit("0.95")));
        telemetry.succeeded(
                KnowledgeRetrievalResult.Status.NO_EVIDENCE,
                30,
                5,
                0,
                List.of());
        telemetry.failed(new BudgetExceededException(), 40);
        telemetry.failed(new KnowledgeDisabledException(), 45);
        telemetry.failed(new KnowledgeException(
                "APVERO_EMBEDDING_PROVIDER_TIMEOUT",
                KnowledgeException.Category.UNPROCESSABLE), 50);
        telemetry.failed(new KnowledgeException(
                "APVERO_KNOWLEDGE_QUERY_SETTLEMENT_CONFLICT",
                KnowledgeException.Category.CONFLICT), 60);

        Set<String> registeredNames = meters.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .collect(Collectors.toSet());
        assertThat(registeredNames).containsExactlyInAnyOrderElementsOf(
                ALLOWED_TAG_KEYS.keySet());
        for (Meter meter : meters.getMeters()) {
            Set<String> keys = meter.getId().getTags().stream()
                    .map(tag -> tag.getKey())
                    .collect(Collectors.toSet());
            assertThat(keys)
                    .as(meter.getId().getName())
                    .isEqualTo(ALLOWED_TAG_KEYS.get(meter.getId().getName()));
            assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getValue())
                        .doesNotContain(
                                "workspace",
                                "tenant",
                                "route",
                                "index",
                                "policy",
                                "query",
                                "content",
                                "00000000-");
                assertThat(tag.getValue().length()).isLessThanOrEqualTo(32);
            });
        }
        assertThat(meters.get("apvero.knowledge.retrieval.request")
                        .tag("outcome", "matches")
                        .tag("failure_family", "none")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("apvero.knowledge.retrieval.request")
                        .tag("outcome", "failed")
                        .tag("failure_family", "disabled")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("apvero.knowledge.retrieval.request")
                        .tag("outcome", "failed")
                        .tag("failure_family", "admission")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("apvero.knowledge.retrieval.score")
                        .tag("bucket", "90_100")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void repeatedRequestIdentitiesCannotIncreaseMetricCardinality() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        KnowledgeRetrievalTelemetry telemetry = new KnowledgeRetrievalTelemetry(meters);
        telemetry.succeeded(
                KnowledgeRetrievalResult.Status.MATCHES,
                1,
                1,
                1,
                List.of(hit("0.91")));
        int baseline = meters.getMeters().size();

        for (int request = 0; request < 1_000; request++) {
            telemetry.succeeded(
                    KnowledgeRetrievalResult.Status.MATCHES,
                    request,
                    request,
                    request,
                    List.of(hit("0.91")));
        }

        assertThat(meters.getMeters()).hasSize(baseline);
    }

    private static KnowledgeRetrievalHit hit(String score) {
        return new KnowledgeRetrievalHit(
                1,
                new BigDecimal(score),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "sha256:" + "a".repeat(64),
                null,
                null,
                KnowledgeSource.Type.TEXT,
                null,
                null,
                null,
                null,
                null);
    }
}
