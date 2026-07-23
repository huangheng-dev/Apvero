package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class KnowledgePropertiesTest {

    @Test
    void rejectsWorkerUrisThatCanCarryCredentials() {
        assertThatThrownBy(() -> properties(false, URI.create("http://user:secret@ai-worker:8090")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_WORKER_BASE_URI_INVALID");
    }

    @Test
    void rejectsNonHttpWorkerUris() {
        assertThatThrownBy(() -> properties(false, URI.create("file:///tmp/worker")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_WORKER_BASE_URI_INVALID");
    }

    private static KnowledgeProperties properties(boolean enabled, URI workerBaseUri) {
        return new KnowledgeProperties(
                enabled, workerBaseUri, Duration.ofSeconds(15), 20_971_520,
                5_242_880, 5_242_880, 2_048, 20_971_520);
    }
}
