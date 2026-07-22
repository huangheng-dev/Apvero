package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class KnowledgeHealthIndicatorTest {

    @Test
    void disabledModeDoesNotRequireAWorker() {
        var indicator = new KnowledgeHealthIndicator(
                new KnowledgeProperties(false, URI.create("http://127.0.0.1:1")),
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
        return new KnowledgeHealthIndicator(new KnowledgeProperties(true, uri), HttpClient.newHttpClient());
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
