package io.apvero.platform.release.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.application.RuntimeMode;
import io.apvero.platform.release.ReleaseException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ReleasePinTelemetryTest {
    @Test
    void recordsOnlyBoundedModeOutcomeAndFailureFamily() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ReleasePinTelemetry telemetry = new ReleasePinTelemetry(meters);

        assertThat(telemetry.observe(RuntimeMode.RAG, () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> telemetry.observe(RuntimeMode.RAG, () -> {
                    throw new ReleaseException(
                            "APVERO_RELEASE_KNOWLEDGE_BINDING_INVALID",
                            ReleaseException.Category.CONFLICT);
                }))
                .isInstanceOf(ReleaseException.class);

        assertThat(meters.get("apvero.release.pin.validation")
                        .tags(
                                "runtime_mode", "rag",
                                "outcome", "succeeded",
                                "failure_family", "none")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("apvero.release.pin.validation")
                        .tags(
                                "runtime_mode", "rag",
                                "outcome", "rejected",
                                "failure_family", "knowledge_binding")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.getMeters().stream()
                        .flatMap(meter -> meter.getId().getTags().stream())
                        .map(tag -> tag.getKey())
                        .distinct())
                .containsExactlyInAnyOrder(
                        "runtime_mode", "outcome", "failure_family");
    }
}
