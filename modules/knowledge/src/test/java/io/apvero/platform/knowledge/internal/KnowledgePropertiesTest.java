package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class KnowledgePropertiesTest {

    @Test
    void rejectsWorkerUrisThatCanCarryCredentials() {
        assertThatThrownBy(() -> new KnowledgeProperties(false, URI.create("http://user:secret@ai-worker:8090")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_WORKER_BASE_URI_INVALID");
    }

    @Test
    void rejectsNonHttpWorkerUris() {
        assertThatThrownBy(() -> new KnowledgeProperties(false, URI.create("file:///tmp/worker")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_WORKER_BASE_URI_INVALID");
    }
}
