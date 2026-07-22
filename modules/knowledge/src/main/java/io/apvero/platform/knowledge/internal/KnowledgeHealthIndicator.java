package io.apvero.platform.knowledge.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("knowledge")
final class KnowledgeHealthIndicator implements HealthIndicator {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final KnowledgeProperties properties;
    private final HttpClient httpClient;

    KnowledgeHealthIndicator(KnowledgeProperties properties, HttpClient knowledgeWorkerHttpClient) {
        this.properties = properties;
        this.httpClient = knowledgeWorkerHttpClient;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.up()
                    .withDetail("enabled", false)
                    .withDetail("mode", "disabled")
                    .build();
        }

        try {
            URI healthUri = properties.workerBaseUri().resolve("/health");
            HttpRequest request = HttpRequest.newBuilder(healthUri)
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) {
                return Health.up()
                        .withDetail("enabled", true)
                        .withDetail("worker", "available")
                        .build();
            }
            return unavailable("unexpected-status");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable("interrupted");
        } catch (Exception exception) {
            return unavailable("unavailable");
        }
    }

    private Health unavailable(String reason) {
        return Health.down()
                .withDetail("enabled", true)
                .withDetail("worker", reason)
                .build();
    }
}
