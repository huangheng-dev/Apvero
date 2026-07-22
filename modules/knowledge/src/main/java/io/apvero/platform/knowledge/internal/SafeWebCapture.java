package io.apvero.platform.knowledge.internal;

import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeException.Category;
import io.apvero.platform.knowledge.KnowledgeSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class SafeWebCapture {
    private static final int MAXIMUM_URI_LENGTH = 2048;

    private final KnowledgeProperties knowledgeProperties;
    private final WebCaptureProperties webProperties;
    private final WebAddressResolver resolver;
    private final WebTransport transport;
    private final MeterRegistry metrics;

    @Autowired
    SafeWebCapture(
            KnowledgeProperties knowledgeProperties,
            WebCaptureProperties webProperties,
            PinnedWebTransport transport,
            MeterRegistry metrics) {
        this(knowledgeProperties, webProperties, host -> List.of(InetAddress.getAllByName(host)), transport, metrics);
    }

    SafeWebCapture(
            KnowledgeProperties knowledgeProperties,
            WebCaptureProperties webProperties,
            WebAddressResolver resolver,
            WebTransport transport,
            MeterRegistry metrics) {
        this.knowledgeProperties = knowledgeProperties;
        this.webProperties = webProperties;
        this.resolver = resolver;
        this.transport = transport;
        this.metrics = metrics;
    }

    CapturedWebSnapshot capture(URI requestedUri) {
        URI current = canonicalize(requestedUri);
        URI source = current;
        for (int redirects = 0; redirects <= webProperties.maxRedirects(); redirects++) {
            InetAddress pinnedAddress = resolvePublicAddress(current.getHost());
            WebResponse response = transport.exchange(
                    current,
                    pinnedAddress,
                    webProperties.connectTimeout(),
                    webProperties.readTimeout(),
                    webProperties.maxHeaderBytes(),
                    knowledgeProperties.maxSnapshotBytes());
            if (isRedirect(response.status())) {
                if (redirects == webProperties.maxRedirects()) {
                    throw problem("APVERO_KNOWLEDGE_WEB_REDIRECT_LIMIT", Category.UNPROCESSABLE);
                }
                String location = response.singleHeader("location");
                if (location == null || location.isBlank()) {
                    throw problem("APVERO_KNOWLEDGE_WEB_REDIRECT_INVALID", Category.UNPROCESSABLE);
                }
                URI redirected;
                try {
                    redirected = canonicalize(current.resolve(location));
                } catch (IllegalArgumentException | KnowledgeException exception) {
                    throw problem("APVERO_KNOWLEDGE_WEB_REDIRECT_INVALID", Category.UNPROCESSABLE);
                }
                if ("https".equals(current.getScheme()) && "http".equals(redirected.getScheme())) {
                    throw problem("APVERO_KNOWLEDGE_WEB_REDIRECT_DOWNGRADE", Category.UNPROCESSABLE);
                }
                current = redirected;
                continue;
            }
            if (response.status() < 200 || response.status() >= 300) {
                throw problem("APVERO_KNOWLEDGE_WEB_FETCH_REJECTED", Category.UNPROCESSABLE);
            }
            String mediaType = supportedMediaType(response.singleHeader("content-type"));
            if (response.body().length == 0) {
                throw problem("APVERO_KNOWLEDGE_CONTENT_REQUIRED", Category.UNPROCESSABLE);
            }
            String metadata = "{\"status\":" + response.status()
                    + ",\"contentType\":\"" + json(mediaType)
                    + "\",\"redirectCount\":" + redirects + "}";
            KnowledgeCapturedSnapshot snapshot = new KnowledgeCapturedSnapshot(
                    KnowledgeSource.Type.WEB,
                    mediaType,
                    null,
                    digest(response.body()),
                    response.body());
            metrics.counter("apvero.knowledge.web.capture", "outcome", "captured").increment();
            return new CapturedWebSnapshot(source, current, metadata, snapshot);
        }
        throw new IllegalStateException("unreachable");
    }

    static URI canonicalize(URI value) {
        if (value == null || value.isOpaque() || value.toASCIIString().length() > MAXIMUM_URI_LENGTH
                || value.getRawAuthority() == null || value.getRawAuthority().isBlank()) {
            throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
        }
        String scheme = value.getScheme() == null ? "" : value.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
        }
        HostPort authority = parseAuthority(value.getRawAuthority());
        int port = authority.port();
        if (port == 0 || port < -1 || port > 65_535) {
            throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
        }
        String rawHost = authority.host();
        String host;
        if (rawHost.startsWith("[") && rawHost.endsWith("]")) {
            String literal = rawHost.substring(1, rawHost.length() - 1);
            if (literal.contains("%")) {
                throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
            }
            try {
                InetAddress parsed = InetAddress.getByName(literal);
                if (!(parsed instanceof Inet6Address)) {
                    throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
                }
                host = parsed.getHostAddress();
            } catch (UnknownHostException exception) {
                throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
            }
        } else {
            try {
                host = IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException exception) {
                throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
            }
        }
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        String path = value.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        try {
            URI canonical = new URI(scheme, null, host, port, path, value.getRawQuery(), null).normalize();
            if (canonical.toASCIIString().length() > MAXIMUM_URI_LENGTH) {
                throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
            }
            return canonical;
        } catch (URISyntaxException exception) {
            throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
        }
    }

    private static HostPort parseAuthority(String authority) {
        if (authority.indexOf('@') >= 0 || authority.indexOf('%') >= 0 || authority.indexOf('\\') >= 0) {
            throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
        }
        String host;
        int port = -1;
        if (authority.startsWith("[")) {
            int closing = authority.indexOf(']');
            if (closing <= 1) {
                throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
            }
            host = authority.substring(0, closing + 1);
            String remainder = authority.substring(closing + 1);
            if (!remainder.isEmpty()) {
                if (!remainder.matches(":[0-9]+")) {
                    throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
                }
                port = parsePort(remainder.substring(1));
            }
        } else {
            int separator = authority.lastIndexOf(':');
            if (separator >= 0) {
                if (authority.indexOf(':') != separator || separator == 0) {
                    throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
                }
                host = authority.substring(0, separator);
                port = parsePort(authority.substring(separator + 1));
            } else {
                host = authority;
            }
        }
        if (host.isBlank()) {
            throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
        }
        return new HostPort(host, port);
    }

    private static int parsePort(String value) {
        if (!value.matches("[0-9]{1,5}")) {
            throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
        }
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65_535) {
            throw problem("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
        }
        return port;
    }

    private InetAddress resolvePublicAddress(String host) {
        List<InetAddress> addresses;
        try {
            String resolvableHost = host.startsWith("[") && host.endsWith("]")
                    ? host.substring(1, host.length() - 1)
                    : host;
            addresses = resolver.resolve(resolvableHost);
        } catch (UnknownHostException exception) {
            throw problem("APVERO_KNOWLEDGE_WEB_DNS_FAILED", Category.UNPROCESSABLE);
        }
        if (addresses == null || addresses.isEmpty()) {
            throw problem("APVERO_KNOWLEDGE_WEB_DNS_FAILED", Category.UNPROCESSABLE);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                metrics.counter("apvero.knowledge.web.capture", "outcome", "ssrf_denied").increment();
                throw problem("APVERO_KNOWLEDGE_WEB_DESTINATION_DENIED", Category.UNPROCESSABLE);
            }
        }
        return addresses.getFirst();
    }

    static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0
                    && first != 10
                    && first != 127
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && second == 0 && third == 0)
                    && !(first == 192 && second == 0 && third == 2)
                    && !(first == 192 && second == 168)
                    && !(first == 192 && second == 88 && third == 99)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113)
                    && first < 224;
        }
        if (address instanceof Inet6Address) {
            boolean globalUnicast = (Byte.toUnsignedInt(bytes[0]) & 0xe0) == 0x20;
            boolean teredo = prefix(bytes, 0x20, 0x01, 0x00, 0x00);
            boolean benchmarking = prefix(bytes, 0x20, 0x01, 0x00, 0x02);
            boolean orchid = prefix(bytes, 0x20, 0x01)
                    && (Byte.toUnsignedInt(bytes[2]) == 0x00)
                    && ((Byte.toUnsignedInt(bytes[3]) & 0xf0) == 0x10
                            || (Byte.toUnsignedInt(bytes[3]) & 0xf0) == 0x20);
            boolean documentation = prefix(bytes, 0x20, 0x01, 0x0d, 0xb8)
                    || (prefix(bytes, 0x3f, 0xff) && (Byte.toUnsignedInt(bytes[2]) & 0xf0) == 0x00);
            boolean transition6to4 = prefix(bytes, 0x20, 0x02);
            return globalUnicast && !teredo && !benchmarking && !orchid && !documentation && !transition6to4;
        }
        return false;
    }

    private static boolean prefix(byte[] address, int... expected) {
        if (address.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (Byte.toUnsignedInt(address[index]) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static String supportedMediaType(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw problem("APVERO_KNOWLEDGE_WEB_MEDIA_UNSUPPORTED", Category.UNSUPPORTED_MEDIA);
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(';');
        String essence = separator < 0 ? normalized : normalized.substring(0, separator).trim();
        if (!List.of("text/html", "text/plain", "text/markdown").contains(essence)) {
            throw problem("APVERO_KNOWLEDGE_WEB_MEDIA_UNSUPPORTED", Category.UNSUPPORTED_MEDIA);
        }
        if (!normalized.chars().allMatch(character -> character >= 0x20 && character != 0x7f)) {
            throw problem("APVERO_KNOWLEDGE_WEB_MEDIA_UNSUPPORTED", Category.UNSUPPORTED_MEDIA);
        }
        return normalized;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static KnowledgeException problem(String code, Category category) {
        return new KnowledgeException(code, category);
    }

    @FunctionalInterface
    interface WebAddressResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    interface WebTransport {
        WebResponse exchange(
                URI target,
                InetAddress pinnedAddress,
                java.time.Duration connectTimeout,
                java.time.Duration readTimeout,
                int maxHeaderBytes,
                int maxBodyBytes);
    }

    record WebResponse(int status, java.util.Map<String, List<String>> headers, byte[] body) {
        WebResponse {
            headers = java.util.Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        String singleHeader(String name) {
            List<String> values = headers.get(name);
            if (values == null || values.isEmpty()) {
                return null;
            }
            if (values.size() != 1) {
                throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
            }
            return values.getFirst();
        }
    }

    record CapturedWebSnapshot(
            URI requestedUri,
            URI finalUri,
            String captureMetadataJson,
            KnowledgeCapturedSnapshot snapshot) {}

    private record HostPort(String host, int port) {}
}
