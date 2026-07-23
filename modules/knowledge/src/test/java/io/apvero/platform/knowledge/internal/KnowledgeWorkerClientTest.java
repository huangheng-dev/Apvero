package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SnapshotStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class KnowledgeWorkerClientTest {
    private HttpServer server;
    private final AtomicReference<Response> response = new AtomicReference<>();
    private final AtomicReference<byte[]> requestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/internal/v1/documents/process", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsPersistedSnapshotIdentityAndAcceptsAValidatedResponse() {
        UUID requestId = UUID.randomUUID();
        SourceRevisionRow revision = revision("hello");
        response.set(json(200, successfulBody(requestId, revision, "hello")));

        var result = client(65_536).process(requestId, revision);

        assertThat(result.sourceRevisionId()).isEqualTo(revision.id());
        assertThat(result.documents()).hasSize(1);
        String multipart = new String(requestBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(multipart)
                .contains("name=\"requestId\"\r\n\r\n" + requestId)
                .contains("name=\"sourceRevisionId\"\r\n\r\n" + revision.id())
                .contains("name=\"contentDigest\"\r\n\r\n" + revision.contentDigest())
                .contains("filename=\"snapshot.bin\"")
                .contains("\r\n\r\nhello\r\n--apvero-");
    }

    @Test
    void rejectsAResponseWhoseIdentityOrChunkDigestDoesNotMatch() {
        UUID requestId = UUID.randomUUID();
        SourceRevisionRow revision = revision("hello");
        String body = successfulBody(requestId, revision, "hello")
                .replace(revision.id().toString(), UUID.randomUUID().toString());
        response.set(json(200, body));

        assertCode(() -> client(65_536).process(requestId, revision),
                "APVERO_KNOWLEDGE_WORKER_INVALID_RESPONSE", false);
    }

    @Test
    void rejectsAResponseWhoseDocumentDigestCannotBeReconstructedFromChunks() {
        UUID requestId = UUID.randomUUID();
        SourceRevisionRow revision = revision("hello");
        String digest = digest("hello".getBytes(StandardCharsets.UTF_8));
        String body = successfulBody(requestId, revision, "hello")
                .replace("\"contentDigest\":\"" + digest + "\",\"chunks\"",
                        "\"contentDigest\":\"sha256:" + "f".repeat(64) + "\",\"chunks\"");
        response.set(json(200, body));

        assertCode(() -> client(65_536).process(requestId, revision),
                "APVERO_KNOWLEDGE_WORKER_INVALID_RESPONSE", false);
    }

    @Test
    void reconstructsAndAcceptsConsistentOverlappingChunks() {
        UUID requestId = UUID.randomUUID();
        SourceRevisionRow revision = revision("abcd");
        response.set(json(200, overlappingBody(requestId, revision)));

        var result = client(65_536).process(requestId, revision);

        assertThat(result.documents().getFirst().chunks()).hasSize(2);
    }

    @Test
    void mapsStableWorkerProblemsWithoutLeakingTheirBody() {
        UUID requestId = UUID.randomUUID();
        SourceRevisionRow revision = revision("hello");
        response.set(json(503, """
                {"type":"https://apvero.dev/problems/worker-processing-timeout",
                 "title":"WORKER_PROCESSING_TIMEOUT","status":503,
                 "code":"WORKER_PROCESSING_TIMEOUT","retryable":true,"requestId":"%s"}
                """.formatted(requestId)));

        assertCode(() -> client(65_536).process(requestId, revision),
                "WORKER_PROCESSING_TIMEOUT", true);
    }

    @Test
    void rejectsAnOversizedWorkerResponseBeforeDeserialization() {
        UUID requestId = UUID.randomUUID();
        SourceRevisionRow revision = revision("hello");
        response.set(json(200, "x".repeat(257)));

        assertCode(() -> client(256).process(requestId, revision),
                "APVERO_KNOWLEDGE_WORKER_INVALID_RESPONSE", false);
    }

    private KnowledgeWorkerClient client(int maximumResponseBytes) {
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        KnowledgeProperties properties = new KnowledgeProperties(
                true, baseUri, Duration.ofSeconds(2), maximumResponseBytes,
                5_242_880, 5_242_880, 2_048, 20_971_520);
        return new KnowledgeWorkerClient(properties, HttpClient.newHttpClient(), new ObjectMapper());
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestBody.set(exchange.getRequestBody().readAllBytes());
        Response current = response.get();
        exchange.getResponseHeaders().set("Content-Type", current.contentType());
        exchange.sendResponseHeaders(current.status(), current.body().length);
        exchange.getResponseBody().write(current.body());
        exchange.close();
    }

    private static Response json(int status, String body) {
        return new Response(status, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static SourceRevisionRow revision(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new SourceRevisionRow(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                digest(bytes), "text/plain", bytes.length, null, "{}", bytes,
                SnapshotStatus.SNAPSHOTTED, null, null, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static String successfulBody(UUID requestId, SourceRevisionRow revision, String text) {
        String digest = digest(text.getBytes(StandardCharsets.UTF_8));
        return """
                {"requestId":"%s","sourceRevisionId":"%s","contentDigest":"%s",
                 "processingProfile":"apvero-default@1.0.0","parserVersion":"apvero-text@1.0.0",
                 "chunkerVersion":"apvero-boundary@1.0.0","documents":[{"ordinal":0,"title":null,
                 "contentDigest":"%s","chunks":[{"ordinal":0,"text":"%s","contentDigest":"%s",
                 "startOffset":0,"endOffset":%d,"anchors":{"page":null,"heading":null,
                 "paragraph":null,"lineStart":1,"lineEnd":1}}]}],"warnings":[]}
                """.formatted(
                requestId, revision.id(), revision.contentDigest(), digest, text, digest,
                text.codePointCount(0, text.length()));
    }

    private static String overlappingBody(UUID requestId, SourceRevisionRow revision) {
        String documentDigest = digest("abcd".getBytes(StandardCharsets.UTF_8));
        String firstDigest = digest("abc".getBytes(StandardCharsets.UTF_8));
        String secondDigest = digest("bcd".getBytes(StandardCharsets.UTF_8));
        return """
                {"requestId":"%s","sourceRevisionId":"%s","contentDigest":"%s",
                 "processingProfile":"apvero-default@1.0.0","parserVersion":"apvero-text@1.0.0",
                 "chunkerVersion":"apvero-boundary@1.0.0","documents":[{"ordinal":0,"title":null,
                 "contentDigest":"%s","chunks":[
                 {"ordinal":0,"text":"abc","contentDigest":"%s","startOffset":0,"endOffset":3,
                  "anchors":{"page":null,"heading":null,"paragraph":null,"lineStart":1,"lineEnd":1}},
                 {"ordinal":1,"text":"bcd","contentDigest":"%s","startOffset":1,"endOffset":4,
                  "anchors":{"page":null,"heading":null,"paragraph":null,"lineStart":1,"lineEnd":1}}]}],
                 "warnings":[]}
                """.formatted(
                requestId, revision.id(), revision.contentDigest(), documentDigest, firstDigest, secondDigest);
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertCode(Runnable action, String code, boolean retryable) {
        assertThatThrownBy(action::run)
                .isInstanceOf(KnowledgeWorkerException.class)
                .satisfies(exception -> {
                    KnowledgeWorkerException problem = (KnowledgeWorkerException) exception;
                    assertThat(problem.code()).isEqualTo(code);
                    assertThat(problem.retryable()).isEqualTo(retryable);
                });
    }

    private record Response(int status, String contentType, byte[] body) {}
}
