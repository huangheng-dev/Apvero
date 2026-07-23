package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class KnowledgeConfigurationTest {
    @Test
    void workerClientUsesTheHttpVersionSupportedByTheInternalWorkerServer() {
        HttpClient client = new KnowledgeConfiguration().knowledgeWorkerHttpClient();

        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }
}
