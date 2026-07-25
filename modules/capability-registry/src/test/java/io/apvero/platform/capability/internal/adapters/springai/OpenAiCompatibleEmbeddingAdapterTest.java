package io.apvero.platform.capability.internal.adapters.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.apvero.platform.capability.EmbeddingReplayPolicy;
import io.apvero.platform.governance.ResolvedSecret;
import io.apvero.platform.governance.SecretReferenceCatalog;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleEmbeddingAdapterTest {
    private static final String PROVIDER_KEY = "provider-key-must-not-leak";
    private static final String PROVIDER_BODY = "APVERO_FAKE provider-body-must-not-leak";

    @Test
    void declaresGenericCompatibilityAsReconciliationRequired() {
        assertThat(OpenAiCompatibleEmbeddingAdapter.IDENTITY)
                .isEqualTo("spring-ai-openai-compatible-embedding");
        assertThat(OpenAiCompatibleEmbeddingAdapter.REPLAY_POLICY)
                .isEqualTo(EmbeddingReplayPolicy.RECONCILIATION_REQUIRED);
    }

    @Test
    void sendsExactProtocolAndNormalizesOrderedVectorsAndUsage() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (StubServer stub = StubServer.start(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {
                      "object": "list",
                      "data": [
                        {"object": "embedding", "index": 0, "embedding": [1.0, 0.0, 0.0]},
                        {"object": "embedding", "index": 1, "embedding": [0.0, 1.0, 0.0]}
                      ],
                      "model": "text-embedding-test",
                      "usage": {"prompt_tokens": 7, "total_tokens": 7}
                    }
                    """);
        })) {
            SecretFixture secrets = new SecretFixture();
            OpenAiCompatibleEmbeddingAdapter adapter =
                    new OpenAiCompatibleEmbeddingAdapter(secrets.catalog(), true);

            OpenAiCompatibleEmbeddingResult result = adapter.execute(request(stub, 5_000));

            assertThat(path.get()).isEqualTo("/v1/embeddings");
            assertThat(authorization.get()).isEqualTo("Bearer " + PROVIDER_KEY);
            assertThat(requestBody.get())
                    .contains("\"model\":\"text-embedding-test\"")
                    .contains("\"dimensions\":3")
                    .contains("\"input\":[\"first\",\"第二\"]");
            assertThat(result.orderedVectors())
                    .containsExactly(new float[] {1f, 0f, 0f}, new float[] {0f, 1f, 0f});
            assertThat(result.actualInputUnits()).isEqualTo(7L);
            assertThat(result.providerRequestIdentity()).isNull();
            assertThat(result.latencyMillis()).isGreaterThanOrEqualTo(0);
            assertThat(secrets.value()).containsOnly('\0');
        }
    }

    @Test
    void rejectsOutOfOrderResponseIndexes() throws Exception {
        try (StubServer stub = StubServer.start(exchange -> respond(exchange, 200, """
                {
                  "object": "list",
                  "data": [
                    {"object": "embedding", "index": 1, "embedding": [0.0, 1.0, 0.0]},
                    {"object": "embedding", "index": 0, "embedding": [1.0, 0.0, 0.0]}
                  ],
                  "model": "text-embedding-test",
                  "usage": {"prompt_tokens": 7, "total_tokens": 7}
                }
                """))) {
            OpenAiCompatibleEmbeddingAdapter adapter =
                    new OpenAiCompatibleEmbeddingAdapter(new SecretFixture().catalog(), true);

            assertThatThrownBy(() -> adapter.execute(request(stub, 5_000)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("APVERO_EMBEDDING_OUTPUT_MAPPING_INVALID");
        }
    }

    @Test
    void performsNoHiddenRetryAndDoesNotExposeProviderDetails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (StubServer stub = StubServer.start(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 500, PROVIDER_BODY);
        })) {
            OpenAiCompatibleEmbeddingAdapter adapter =
                    new OpenAiCompatibleEmbeddingAdapter(new SecretFixture().catalog(), true);

            assertThatThrownBy(() -> adapter.execute(request(stub, 5_000)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("APVERO_EMBEDDING_PROVIDER_REJECTED")
                    .message()
                    .doesNotContain(PROVIDER_BODY, PROVIDER_KEY, stub.baseUrl());
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void normalizesTimeoutWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (StubServer stub = StubServer.start(exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(1_500);
                respond(exchange, 200, validResponse());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            } catch (IOException exception) {
                exchange.close();
            }
        })) {
            OpenAiCompatibleEmbeddingAdapter adapter =
                    new OpenAiCompatibleEmbeddingAdapter(new SecretFixture().catalog(), true);

            assertThatThrownBy(() -> adapter.execute(request(stub, 1_000)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("APVERO_EMBEDDING_PROVIDER_TIMEOUT");
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void failsClosedBeforeSecretResolutionWhenRealProvidersAreDisabled() {
        SecretReferenceCatalog secrets = mock(SecretReferenceCatalog.class);
        OpenAiCompatibleEmbeddingAdapter adapter = new OpenAiCompatibleEmbeddingAdapter(secrets, false);

        assertThatThrownBy(() -> adapter.execute(new OpenAiCompatibleEmbeddingRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "https://provider.invalid/v1",
                        "text-embedding-test",
                        3,
                        5_000,
                        List.of("first"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_REAL_PROVIDER_DISABLED");
        verify(secrets, never()).resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void normalizesSecretResolutionFailure() {
        SecretReferenceCatalog secrets = mock(SecretReferenceCatalog.class);
        UUID workspaceId = UUID.randomUUID();
        UUID secretId = UUID.randomUUID();
        when(secrets.resolve(workspaceId, secretId))
                .thenThrow(new IllegalArgumentException("unsafe secret locator detail"));
        OpenAiCompatibleEmbeddingAdapter adapter = new OpenAiCompatibleEmbeddingAdapter(secrets, true);

        assertThatThrownBy(() -> adapter.execute(new OpenAiCompatibleEmbeddingRequest(
                        workspaceId,
                        secretId,
                        "https://provider.invalid/v1",
                        "text-embedding-test",
                        3,
                        5_000,
                        List.of("first"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_EMBEDDING_SECRET_UNAVAILABLE")
                .message()
                .doesNotContain("locator");
    }

    private static OpenAiCompatibleEmbeddingRequest request(StubServer stub, long timeoutMs) {
        return new OpenAiCompatibleEmbeddingRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                stub.baseUrl(),
                "text-embedding-test",
                3,
                timeoutMs,
                List.of("first", "第二"));
    }

    private static String validResponse() {
        return """
                {
                  "object": "list",
                  "data": [
                    {"object": "embedding", "index": 0, "embedding": [1.0, 0.0, 0.0]},
                    {"object": "embedding", "index": 1, "embedding": [0.0, 1.0, 0.0]}
                  ],
                  "model": "text-embedding-test",
                  "usage": {"prompt_tokens": 7, "total_tokens": 7}
                }
                """;
    }

    private static void respond(HttpExchange exchange, int status, String response) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record SecretFixture(SecretReferenceCatalog catalog, char[] value) {
        private SecretFixture() {
            this(mock(SecretReferenceCatalog.class), PROVIDER_KEY.toCharArray());
            when(catalog.resolve(
                            org.mockito.ArgumentMatchers.any(UUID.class),
                            org.mockito.ArgumentMatchers.any(UUID.class)))
                    .thenReturn(new ResolvedSecret(value));
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record StubServer(HttpServer server) implements AutoCloseable {
        private static StubServer start(Handler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/embeddings", handler::handle);
            server.start();
            return new StubServer(server);
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
