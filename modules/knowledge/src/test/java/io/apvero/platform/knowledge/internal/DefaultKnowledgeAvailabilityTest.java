package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.knowledge.KnowledgeDisabledException;
import java.net.URI;
import org.junit.jupiter.api.Test;

class DefaultKnowledgeAvailabilityTest {

    private static final URI WORKER_URI = URI.create("http://ai-worker:8090");

    @Test
    void disabledIsTheFailClosedDefault() {
        var availability = new DefaultKnowledgeAvailability(new KnowledgeProperties(false, WORKER_URI));

        assertThat(availability.isEnabled()).isFalse();
        assertThatThrownBy(availability::requireEnabled)
                .isInstanceOf(KnowledgeDisabledException.class)
                .hasMessage(KnowledgeDisabledException.CODE);
    }

    @Test
    void explicitEnablementOpensTheGate() {
        var availability = new DefaultKnowledgeAvailability(new KnowledgeProperties(true, WORKER_URI));

        assertThat(availability.isEnabled()).isTrue();
        availability.requireEnabled();
    }
}
