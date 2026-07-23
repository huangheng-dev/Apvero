package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class KnowledgeHealthIndicatorTest {

    @Test
    void disabledModeDoesNotRequireAWorker() {
        var indicator = new KnowledgeHealthIndicator(
                properties(false, URI.create("http://127.0.0.1:1")),
                HttpClient.newHttpClient());

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("enabled", false)
                .containsEntry("mode", "disabled");
    }

    @Test
    void enabledModeRequiresAHealthyInternalWorker() throws Exception {
        HttpServer server = serverRespondingWith(200);
        try {
            var indicator = indicatorFor(server);

            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            assertThat(indicator.health().getDetails()).containsEntry("worker", "available");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enabledModeFailsClosedWhenWorkerIsUnhealthy() throws Exception {
        HttpServer server = serverRespondingWith(503);
        try {
            var health = indicatorFor(server).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("worker", "unexpected-status");
        } finally {
            server.stop(0);
        }
    }

    private KnowledgeHealthIndicator indicatorFor(HttpServer server) {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new KnowledgeHealthIndicator(properties(true, uri), HttpClient.newHttpClient());
    }

    private KnowledgeProperties properties(boolean enabled, URI uri) {
        return new KnowledgeProperties(
                enabled, uri, Duration.ofSeconds(15), 20_971_520,
                5_242_880, 5_242_880, 2_048, 20_971_520);
    }

    private HttpServer serverRespondingWith(int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server;
    }
}
