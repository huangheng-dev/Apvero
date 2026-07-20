package io.apvero.platform.runtime.adapters.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import io.apvero.platform.capability.CapabilityCatalog;
import io.apvero.platform.capability.RuntimeConfiguration;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleasePurpose;
import io.apvero.platform.release.ReleaseStatus;
import io.apvero.platform.runtime.ProviderRequest;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpringAiOpenAiCompatibleProviderTest {
    @Test
    void executesThroughSpringAiAndNormalizesUsageAndCost() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":1,"
                    + "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Hello from the provider stub\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper json = new ObjectMapper();
            CapabilityCatalog catalog = mock(CapabilityCatalog.class);
            UUID workspaceId = UUID.randomUUID();
            var configuration = new RuntimeConfiguration(
                    "OPENAI_COMPATIBLE", "provider-test", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-model", "test-key".toCharArray(), "Answer as {{persona}}.", 5000, 64,
                    new BigDecimal("0.2"), 1_000_000, 2_000_000, false);
            when(catalog.resolve(workspaceId, "test-route@1", "test-prompt@1")).thenReturn(configuration);
            var provider = new SpringAiOpenAiCompatibleProvider(catalog, json, true);
            var manifest = json.createObjectNode();
            manifest.put("modelRouteVersion", "test-route@1");
            manifest.put("promptVersion", "test-prompt@1");
            var release = new ReleaseBundle(UUID.randomUUID(), UUID.randomUUID(), workspaceId, UUID.randomUUID(),
                    "0.0.0-preview-test", "a".repeat(64), manifest, ReleaseStatus.RELEASED,
                    ReleasePurpose.PREVIEW, OffsetDateTime.now(ZoneOffset.UTC).plusHours(1), OffsetDateTime.now(ZoneOffset.UTC));
            var input = json.createObjectNode();
            input.put("message", "Hello");
            input.put("persona", "engineer");

            var result = provider.execute(new ProviderRequest(release, input, "trace-test"));

            assertThat(result.output().get("message").stringValue()).isEqualTo("Hello from the provider stub");
            assertThat(result.promptTokens()).isEqualTo(5);
            assertThat(result.completionTokens()).isEqualTo(3);
            assertThat(result.costMicros()).isEqualTo(11);
        } finally {
            server.stop(0);
        }
    }
}
