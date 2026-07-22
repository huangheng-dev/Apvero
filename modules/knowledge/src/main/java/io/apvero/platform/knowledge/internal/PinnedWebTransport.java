package io.apvero.platform.knowledge.internal;

import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeException.Category;
import io.apvero.platform.knowledge.internal.SafeWebCapture.WebResponse;
import io.apvero.platform.knowledge.internal.SafeWebCapture.WebTransport;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;

@Component
final class PinnedWebTransport implements WebTransport {
    private static final int MAXIMUM_CHUNK_LINE_BYTES = 128;

    @Override
    public WebResponse exchange(
            URI target,
            InetAddress pinnedAddress,
            Duration connectTimeout,
            Duration readTimeout,
            int maxHeaderBytes,
            int maxBodyBytes) {
        int port = target.getPort() >= 0 ? target.getPort() : ("https".equals(target.getScheme()) ? 443 : 80);
        try (Socket socket = connect(target, pinnedAddress, port, connectTimeout, readTimeout)) {
            writeRequest(socket.getOutputStream(), target, port);
            return readResponse(socket.getInputStream(), maxHeaderBytes, maxBodyBytes);
        } catch (java.net.SocketTimeoutException exception) {
            throw problem("APVERO_KNOWLEDGE_WEB_FETCH_TIMEOUT", Category.UNPROCESSABLE);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof KnowledgeException knowledgeException) {
                throw knowledgeException;
            }
            throw problem("APVERO_KNOWLEDGE_WEB_FETCH_FAILED", Category.UNPROCESSABLE);
        }
    }

    private static Socket connect(
            URI target,
            InetAddress pinnedAddress,
            int port,
            Duration connectTimeout,
            Duration readTimeout) throws IOException {
        Socket direct = new Socket();
        direct.connect(new InetSocketAddress(pinnedAddress, port), milliseconds(connectTimeout));
        direct.setSoTimeout(milliseconds(readTimeout));
        if (!"https".equals(target.getScheme())) {
            return direct;
        }
        try {
            String tlsHost = unbracketedHost(target.getHost());
            SSLSocket secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(direct, tlsHost, port, true);
            SSLParameters parameters = secure.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            if (!isIpLiteral(tlsHost)) {
                parameters.setServerNames(List.of(new SNIHostName(tlsHost)));
            }
            secure.setSSLParameters(parameters);
            secure.setSoTimeout(milliseconds(readTimeout));
            secure.startHandshake();
            return secure;
        } catch (IOException | RuntimeException exception) {
            try {
                direct.close();
            } catch (IOException ignored) {
                // Preserve the handshake failure.
            }
            throw exception;
        }
    }

    private static void writeRequest(OutputStream output, URI target, int port) throws IOException {
        String path = target.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (target.getRawQuery() != null) {
            path += "?" + target.getRawQuery();
        }
        String host = unbracketedHost(target.getHost());
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        boolean defaultPort = ("http".equals(target.getScheme()) && port == 80)
                || ("https".equals(target.getScheme()) && port == 443);
        String authority = defaultPort ? host : host + ":" + port;
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + authority + "\r\n"
                + "User-Agent: Apvero-Web-Capture/1.0\r\n"
                + "Accept: text/html, text/plain, text/markdown\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static WebResponse readResponse(InputStream input, int maxHeaderBytes, int maxBodyBytes)
            throws IOException {
        byte[] headerBytes = readHeaders(input, maxHeaderBytes);
        String headerBlock = new String(headerBytes, StandardCharsets.ISO_8859_1);
        String[] lines = headerBlock.split("\\r\\n", -1);
        if (lines.length < 2 || !lines[0].matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: .*)?")) {
            throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
        }
        int status = Integer.parseInt(lines[0].substring(9, 12));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty()) {
                continue;
            }
            if (Character.isWhitespace(line.charAt(0))) {
                throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
            }
            String name = line.substring(0, separator).toLowerCase(Locale.ROOT);
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) {
                throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
            }
            String value = line.substring(separator + 1).trim();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        rejectUnsupportedEncoding(headers);
        byte[] body = readBody(input, headers, maxBodyBytes, status);
        return new WebResponse(status, headers, body);
    }

    private static byte[] readHeaders(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        int[] terminator = {'\r', '\n', '\r', '\n'};
        while (output.size() < maximum) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException();
            }
            output.write(value);
            matched = value == terminator[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
            if (matched == terminator.length) {
                byte[] bytes = output.toByteArray();
                return java.util.Arrays.copyOf(bytes, bytes.length - terminator.length);
            }
        }
        throw problem("APVERO_KNOWLEDGE_WEB_HEADERS_TOO_LARGE", Category.UNPROCESSABLE);
    }

    private static byte[] readBody(
            InputStream input,
            Map<String, List<String>> headers,
            int maximum,
            int status) throws IOException {
        if (status == 204 || status == 304 || (status >= 100 && status < 200)) {
            return new byte[0];
        }
        List<String> transferEncoding = headers.get("transfer-encoding");
        List<String> contentLength = headers.get("content-length");
        if (transferEncoding != null && contentLength != null) {
            throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
        }
        if (transferEncoding != null) {
            if (transferEncoding.size() != 1 || !"chunked".equalsIgnoreCase(transferEncoding.getFirst().trim())) {
                throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
            }
            return readChunked(input, maximum);
        }
        if (contentLength != null) {
            if (contentLength.size() != 1 || !contentLength.getFirst().matches("[0-9]+")) {
                throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
            }
            long length = Long.parseLong(contentLength.getFirst());
            if (length > maximum) {
                throw problem("APVERO_KNOWLEDGE_CONTENT_TOO_LARGE", Category.CONTENT_TOO_LARGE);
            }
            return readExactly(input, (int) length);
        }
        return readUntilEof(input, maximum);
    }

    private static byte[] readChunked(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(input, MAXIMUM_CHUNK_LINE_BYTES);
            int extension = line.indexOf(';');
            String sizeText = (extension < 0 ? line : line.substring(0, extension)).trim();
            if (!sizeText.matches("[0-9a-fA-F]+")) {
                throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
            }
            long size = Long.parseLong(sizeText, 16);
            if (size == 0) {
                int trailerBytes = 0;
                String trailer;
                while (!(trailer = readLine(input, 8192)).isEmpty()) {
                    trailerBytes += trailer.length() + 2;
                    if (trailerBytes > 65_536) {
                        throw problem("APVERO_KNOWLEDGE_WEB_HEADERS_TOO_LARGE", Category.UNPROCESSABLE);
                    }
                }
                return output.toByteArray();
            }
            if (size > maximum - output.size()) {
                throw problem("APVERO_KNOWLEDGE_CONTENT_TOO_LARGE", Category.CONTENT_TOO_LARGE);
            }
            output.write(readExactly(input, (int) size));
            if (input.read() != '\r' || input.read() != '\n') {
                throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
            }
        }
    }

    private static String readLine(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() < maximum) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException();
            }
            if (value == '\r') {
                if (input.read() != '\n') {
                    throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
                }
                return output.toString(StandardCharsets.US_ASCII);
            }
            output.write(value);
        }
        throw problem("APVERO_KNOWLEDGE_WEB_RESPONSE_INVALID", Category.UNPROCESSABLE);
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException();
        }
        return bytes;
    }

    private static byte[] readUntilEof(InputStream input, int maximum) throws IOException {
        byte[] bytes = input.readNBytes(maximum + 1);
        if (bytes.length > maximum) {
            throw problem("APVERO_KNOWLEDGE_CONTENT_TOO_LARGE", Category.CONTENT_TOO_LARGE);
        }
        return bytes;
    }

    private static void rejectUnsupportedEncoding(Map<String, List<String>> headers) {
        List<String> encodings = headers.get("content-encoding");
        if (encodings != null
                && (encodings.size() != 1 || !"identity".equalsIgnoreCase(encodings.getFirst().trim()))) {
            throw problem("APVERO_KNOWLEDGE_WEB_ENCODING_UNSUPPORTED", Category.UNSUPPORTED_MEDIA);
        }
    }

    private static int milliseconds(Duration duration) {
        return Math.max(1, Math.toIntExact(Math.min(Integer.MAX_VALUE, duration.toMillis())));
    }

    private static String unbracketedHost(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static boolean isIpLiteral(String host) {
        if (host.contains(":")) {
            return true;
        }
        return host.matches("[0-9.]+");
    }

    private static KnowledgeException problem(String code, Category category) {
        return new KnowledgeException(code, category);
    }
}
