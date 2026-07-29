package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildOperations.ScanOutcome;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildOperations.Snapshot;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.EntryKindTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.ErrorCategoryTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.OutcomeTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.PublicationTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.PublicationValidationTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.QualityTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.StaleOperationTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KnowledgeIndexBuildTelemetryTest {
    private static final Map<String, Set<String>> ALLOWED_TAG_KEYS = Map.ofEntries(
            Map.entry("apvero.knowledge.index.build.claimed", Set.of("step")),
            Map.entry("apvero.knowledge.index.build.queue.wait", Set.of("step")),
            Map.entry(
                    "apvero.knowledge.index.build.step.duration",
                    Set.of("step", "outcome", "error_category")),
            Map.entry(
                    "apvero.knowledge.index.build.attempt",
                    Set.of("step", "attempt_bucket")),
            Map.entry("apvero.knowledge.index.build.batch.items", Set.of("outcome")),
            Map.entry(
                    "apvero.knowledge.index.build.batch.units",
                    Set.of("quality", "outcome")),
            Map.entry(
                    "apvero.knowledge.index.build.entries",
                    Set.of("kind", "outcome")),
            Map.entry(
                    "apvero.knowledge.index.build.retry",
                    Set.of("step", "error_category")),
            Map.entry(
                    "apvero.knowledge.index.build.stale.lease",
                    Set.of("step", "operation")),
            Map.entry(
                    "apvero.knowledge.index.build.recovery",
                    Set.of("action", "outcome")),
            Map.entry(
                    "apvero.knowledge.index.build.publication.validation",
                    Set.of("outcome", "error_category")),
            Map.entry("apvero.knowledge.index.build.publication", Set.of("outcome")),
            Map.entry("apvero.knowledge.index.build.inflight", Set.of()),
            Map.entry("apvero.knowledge.index.build.oldest.eligible.age", Set.of()),
            Map.entry("apvero.knowledge.index.build.reconciliation", Set.of()));

    @Test
    void registersEveryRequiredFamilyWithOnlyBoundedTagsAndNoIdentity() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        KnowledgeIndexBuildTelemetry telemetry = new KnowledgeIndexBuildTelemetry(meters);
        AtomicInteger inFlight = new AtomicInteger(2);
        AtomicReference<Snapshot> snapshot =
                new AtomicReference<>(snapshot(8L, 3L));
        telemetry.bindGauges(inFlight::get, snapshot::get);

        telemetry.claimed(BuildStep.EMBEDDING);
        telemetry.queueWait(BuildStep.EMBEDDING, 10);
        telemetry.stepDuration(
                BuildStep.EMBEDDING, OutcomeTag.SUCCESS, ErrorCategoryTag.NONE, 20);
        telemetry.attempt(BuildStep.EMBEDDING, 7);
        telemetry.batchItems(OutcomeTag.SUCCESS, 4);
        telemetry.batchUnits(QualityTag.ACTUAL, OutcomeTag.SUCCESS, 12);
        telemetry.entries(EntryKindTag.EMBEDDED, OutcomeTag.SUCCESS, 4);
        telemetry.retry(BuildStep.EMBEDDING, ErrorCategoryTag.TRANSIENT);
        telemetry.staleLease(BuildStep.INDEXING, StaleOperationTag.TRANSITION);
        telemetry.recovery(RecoveryAction.REPLAY, OutcomeTag.REPLAYED);
        telemetry.publicationValidation(
                PublicationValidationTag.ADVANCED, ErrorCategoryTag.NONE);
        telemetry.publication(PublicationTag.PUBLISHED);

        Set<String> registeredNames = meters.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .collect(Collectors.toSet());
        assertThat(registeredNames).containsAll(ALLOWED_TAG_KEYS.keySet());
        for (Meter meter : meters.getMeters()) {
            Set<String> keys = meter.getId().getTags().stream()
                    .map(tag -> tag.getKey())
                    .collect(Collectors.toSet());
            assertThat(keys)
                    .as(meter.getId().getName())
                    .isEqualTo(ALLOWED_TAG_KEYS.get(meter.getId().getName()));
            assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> {
                        assertThat(tag.getValue())
                                .doesNotContain(
                                        "00000000-",
                                        "workspace",
                                        "tenant",
                                        "route@",
                                        "index-build-runner-");
                        assertThat(tag.getValue().length()).isLessThanOrEqualTo(32);
                    });
        }
        assertThat(meters.get("apvero.knowledge.index.build.inflight")
                        .gauge()
                        .value())
                .isEqualTo(2);
        assertThat(meters.get("apvero.knowledge.index.build.oldest.eligible.age")
                        .gauge()
                        .value())
                .isEqualTo(8);
        assertThat(meters.get("apvero.knowledge.index.build.reconciliation")
                        .gauge()
                        .value())
                .isEqualTo(3);
    }

    private static Snapshot snapshot(Long oldestAge, Long reconciliationCount) {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new Snapshot(
                now,
                now,
                now,
                ScanOutcome.SUCCESS,
                0,
                oldestAge,
                reconciliationCount);
    }
}
