package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.knowledge.KnowledgeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SafeWebCaptureTest {
    private static final WebCaptureProperties LIMITS =
            new WebCaptureProperties(3, 8192, Duration.ofMillis(100), Duration.ofMillis(100));

    @Test
    void canonicalizesInternationalHostDefaultPortAndFragment() {
        URI canonical = SafeWebCapture.canonicalize(
                URI.create("HTTPS://BÜCHER.example:443/a/../guide?q=1#private"));

        assertThat(canonical.toASCIIString()).isEqualTo("https://xn--bcher-kva.example/guide?q=1");
        assertCode(() -> SafeWebCapture.canonicalize(URI.create("file:///etc/passwd")),
                "APVERO_KNOWLEDGE_WEB_URI_INVALID");
        assertCode(() -> SafeWebCapture.canonicalize(URI.create("https://user:secret@example.com/")),
                "APVERO_KNOWLEDGE_WEB_URI_INVALID");
    }

    @Test
    void deniesPrivateMetadataDocumentationAndIpv6Destinations() throws Exception {
        for (String address : List.of(
                "127.0.0.1", "10.0.0.1", "169.254.169.254", "192.168.1.1",
                "100.64.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1",
                "::1", "fe80::1", "fc00::1", "2001:db8::1", "2001::1", "2002:0808:0808::1")) {
            assertThat(SafeWebCapture.isPublic(InetAddress.getByName(address))).as(address).isFalse();
        }
        assertThat(SafeWebCapture.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(SafeWebCapture.isPublic(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
    }

    @Test
    void connectsOnlyToPinnedValidatedAddressAndCapturesSafeMetadata() throws Exception {
        InetAddress pinned = InetAddress.getByName("8.8.8.8");
        RecordingTransport transport = new RecordingTransport(new SafeWebCapture.WebResponse(
                200, Map.of("content-type", List.of("text/html; charset=UTF-8")), "<h1>ok</h1>".getBytes()));
        SafeWebCapture capture = capture(host -> List.of(pinned), transport);

        SafeWebCapture.CapturedWebSnapshot result = capture.capture(URI.create("https://example.com/start"));

        assertThat(transport.addresses).containsExactly(pinned);
        assertThat(result.snapshot().sourceType().name()).isEqualTo("WEB");
        assertThat(result.snapshot().mediaType()).isEqualTo("text/html");
        assertThat(result.captureMetadataJson())
                .isEqualTo("{\"status\":200,\"contentType\":\"text/html; charset=utf-8\",\"redirectCount\":0}");
        assertThat(result.captureMetadataJson()).doesNotContain("example.com");
    }

    @Test
    void revalidatesEveryRedirectAndBlocksDnsRebinding() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        InetAddress privateAddress = InetAddress.getByName("127.0.0.1");
        RecordingTransport redirect = new RecordingTransport(new SafeWebCapture.WebResponse(
                302, Map.of("location", List.of("/next")), new byte[0]));
        ArrayDeque<List<InetAddress>> answers = new ArrayDeque<>();
        answers.add(List.of(publicAddress));
        answers.add(List.of(privateAddress));
        SafeWebCapture capture = capture(host -> answers.removeFirst(), redirect);

        assertCode(() -> capture.capture(URI.create("https://example.com/start")),
                "APVERO_KNOWLEDGE_WEB_DESTINATION_DENIED");
        assertThat(redirect.addresses).containsExactly(publicAddress);
    }

    @Test
    void rejectsRedirectToMetadataEvenWhenTheFirstHopIsPublic() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        RecordingTransport redirect = new RecordingTransport(new SafeWebCapture.WebResponse(
                302, Map.of("location", List.of("https://169.254.169.254/latest/meta-data")), new byte[0]));
        SafeWebCapture capture = capture(
                host -> List.of("example.com".equals(host) ? publicAddress : InetAddress.getByName(host)),
                redirect);

        assertCode(() -> capture.capture(URI.create("https://example.com/start")),
                "APVERO_KNOWLEDGE_WEB_DESTINATION_DENIED");
    }

    @Test
    void rejectsHttpsRedirectDowngrade() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        RecordingTransport redirect = new RecordingTransport(new SafeWebCapture.WebResponse(
                302, Map.of("location", List.of("http://example.com/plain")), new byte[0]));
        SafeWebCapture capture = capture(host -> List.of(publicAddress), redirect);

        assertCode(() -> capture.capture(URI.create("https://example.com/start")),
                "APVERO_KNOWLEDGE_WEB_REDIRECT_DOWNGRADE");
    }

    private static SafeWebCapture capture(
            SafeWebCapture.WebAddressResolver resolver,
            SafeWebCapture.WebTransport transport) {
        KnowledgeProperties knowledge = new KnowledgeProperties(
                true, URI.create("http://ai-worker:8090"), Duration.ofSeconds(15), 20_971_520,
                1024, 1024, 8, 4096);
        return new SafeWebCapture(knowledge, LIMITS, resolver, transport, new SimpleMeterRegistry());
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo(code);
    }

    private static final class RecordingTransport implements SafeWebCapture.WebTransport {
        private final SafeWebCapture.WebResponse response;
        private final List<InetAddress> addresses = new ArrayList<>();

        private RecordingTransport(SafeWebCapture.WebResponse response) {
            this.response = response;
        }

        @Override
        public SafeWebCapture.WebResponse exchange(
                URI target,
                InetAddress pinnedAddress,
                Duration connectTimeout,
                Duration readTimeout,
                int maxHeaderBytes,
                int maxBodyBytes) {
            addresses.add(pinnedAddress);
            return response;
        }
    }
}
