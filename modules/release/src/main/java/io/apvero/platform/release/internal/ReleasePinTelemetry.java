package io.apvero.platform.release.internal;

import io.apvero.platform.application.RuntimeMode;
import io.apvero.platform.release.ReleaseException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
final class ReleasePinTelemetry {
    private final MeterRegistry meters;

    ReleasePinTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    <T> T observe(RuntimeMode mode, Supplier<T> operation) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "succeeded";
        String failureFamily = "none";
        try {
            return operation.get();
        } catch (ReleaseException exception) {
            outcome = "rejected";
            failureFamily = family(exception.code());
            throw exception;
        } catch (RuntimeException exception) {
            outcome = "failed";
            failureFamily = "internal";
            throw exception;
        } finally {
            String runtimeMode = mode == null
                    ? "unknown"
                    : mode.name().toLowerCase(java.util.Locale.ROOT);
            meters.counter(
                            "apvero.release.pin.validation",
                            "runtime_mode",
                            runtimeMode,
                            "outcome",
                            outcome,
                            "failure_family",
                            failureFamily)
                    .increment();
            sample.stop(Timer.builder("apvero.release.pin.validation.latency")
                    .tag("runtime_mode", runtimeMode)
                    .tag("outcome", outcome)
                    .tag("failure_family", failureFamily)
                    .register(meters));
        }
    }

    private static String family(String code) {
        if ("APVERO_RELEASE_KNOWLEDGE_BINDING_INVALID".equals(code)) {
            return "knowledge_binding";
        }
        if ("APVERO_RELEASE_MANIFEST_UNSUPPORTED".equals(code)) {
            return "manifest_unsupported";
        }
        if ("APVERO_RELEASE_MANIFEST_INVALID".equals(code)) {
            return "manifest_invalid";
        }
        return "release";
    }
}
