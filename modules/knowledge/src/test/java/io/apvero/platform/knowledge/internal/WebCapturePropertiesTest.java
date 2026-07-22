package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebCapturePropertiesTest {
    @Test
    void rejectsUnboundedOrEffectivelyInfiniteTimeouts() {
        assertThatThrownBy(() -> new WebCaptureProperties(
                        5, 65536, Duration.ofNanos(1), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_WEB_TIMEOUT_INVALID");
        assertThatThrownBy(() -> new WebCaptureProperties(
                        5, 65536, Duration.ofSeconds(2), Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_WEB_TIMEOUT_INVALID");
    }
}
