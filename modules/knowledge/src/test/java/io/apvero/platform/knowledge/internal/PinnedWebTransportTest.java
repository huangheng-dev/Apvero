package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.knowledge.KnowledgeException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PinnedWebTransportTest {
    private final PinnedWebTransport transport = new PinnedWebTransport();

    @Test
    void usesDirectPinnedSocketAndReadsBoundedIdentityResponse() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> response = respond(server,
                    "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok", 0);

            SafeWebCapture.WebResponse captured = exchange(server, 32);

            assertThat(captured.status()).isEqualTo(200);
            assertThat(captured.body()).containsExactly((byte) 'o', (byte) 'k');
            response.join();
        }
    }

    @Test
    void rejectsOversizedBodiesBeforeReadingThem() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> response = respond(server,
                    "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 100\r\n\r\n", 0);

            assertCode(() -> exchange(server, 8), "APVERO_KNOWLEDGE_CONTENT_TOO_LARGE");
            response.join();
        }
    }

    @Test
    void rejectsUnsupportedCompressionAndTimesOutBoundedly() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> response = respond(server,
                    "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Encoding: gzip\r\nContent-Length: 0\r\n\r\n", 0);
            assertCode(() -> exchange(server, 32), "APVERO_KNOWLEDGE_WEB_ENCODING_UNSUPPORTED");
            response.join();
        }
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> response = respond(server, "", 300);
            assertCode(() -> exchange(server, 32), "APVERO_KNOWLEDGE_WEB_FETCH_TIMEOUT");
            response.join();
        }
    }

    private SafeWebCapture.WebResponse exchange(ServerSocket server, int maximumBody) {
        return transport.exchange(
                URI.create("http://example.com:" + server.getLocalPort() + "/resource"),
                server.getInetAddress(), Duration.ofSeconds(1), Duration.ofMillis(100), 8192, maximumBody);
    }

    private static CompletableFuture<Void> respond(ServerSocket server, String payload, long delayMillis) {
        return CompletableFuture.runAsync(() -> {
            try (var socket = server.accept()) {
                socket.getInputStream().readNBytes(1);
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                OutputStream output = socket.getOutputStream();
                output.write(payload.getBytes(StandardCharsets.ISO_8859_1));
                output.flush();
            } catch (java.net.SocketException exception) {
                // A bounded client is allowed to close before the delayed server writes.
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo(code);
    }
}
