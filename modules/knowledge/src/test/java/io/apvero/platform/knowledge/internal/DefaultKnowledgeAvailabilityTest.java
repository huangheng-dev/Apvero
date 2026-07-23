package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.knowledge.KnowledgeDisabledException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DefaultKnowledgeAvailabilityTest {

    private static final URI WORKER_URI = URI.create("http://ai-worker:8090");

    @Test
    void disabledIsTheFailClosedDefault() {
        var availability = new DefaultKnowledgeAvailability(properties(false));

        assertThat(availability.isEnabled()).isFalse();
        assertThatThrownBy(availability::requireEnabled)
                .isInstanceOf(KnowledgeDisabledException.class)
                .hasMessage(KnowledgeDisabledException.CODE);
    }

    @Test
    void explicitEnablementOpensTheGate() {
        var availability = new DefaultKnowledgeAvailability(properties(true));

        assertThat(availability.isEnabled()).isTrue();
        availability.requireEnabled();
    }

    private static KnowledgeProperties properties(boolean enabled) {
        return new KnowledgeProperties(
                enabled, WORKER_URI, Duration.ofSeconds(15), 20_971_520,
                5_242_880, 5_242_880, 2_048, 20_971_520);
    }
}
