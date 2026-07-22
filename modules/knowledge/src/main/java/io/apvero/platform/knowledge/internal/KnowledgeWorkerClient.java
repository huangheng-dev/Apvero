package io.apvero.platform.knowledge.internal;

import static io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.PROCESSING_PROFILE;

import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedBatch;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedChunk;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedDocument;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessingWarning;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.SourceAnchors;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.WorkerProblem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class KnowledgeWorkerClient {
    private static final Pattern DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");
    private static final Pattern VERSION =
            Pattern.compile("^[a-z0-9][a-z0-9.-]*@[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?$");
    private static final Set<String> WARNING_CODES = Set.of(
            "EMPTY_PAGE",
            "UNSUPPORTED_ELEMENT_SKIPPED",
            "TRUNCATED_METADATA",
            "NORMALIZED_INVALID_CHARACTER");

    private final KnowledgeProperties properties;
    private final HttpClient http;
    private final ObjectMapper json;

    KnowledgeWorkerClient(KnowledgeProperties properties, HttpClient knowledgeWorkerHttpClient, ObjectMapper json) {
        this.properties = properties;
        this.http = knowledgeWorkerHttpClient;
        this.json = json;
    }

    ProcessedBatch process(UUID requestId, SourceRevisionRow revision) {
        if (requestId == null || revision == null || revision.snapshotBytes() == null) {
            throw invalidResponse();
        }
        String boundary = "apvero-" + UUID.randomUUID();
        byte[] requestBody = multipart(boundary, requestId, revision);
        HttpRequest request = HttpRequest.newBuilder(workerEndpoint())
                .timeout(properties.workerReadTimeout())
                .header("Accept", "application/json, application/problem+json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = readBounded(response.body(), properties.maxWorkerResponseBytes());
            if (response.statusCode() != 200) {
                throw workerProblem(body);
            }
            ProcessedBatch result = json.readValue(body, ProcessedBatch.class);
            validate(result, requestId, revision);
            return result;
        } catch (KnowledgeWorkerException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KnowledgeWorkerException("APVERO_KNOWLEDGE_WORKER_INTERRUPTED", true);
        } catch (IOException | RuntimeException exception) {
            throw new KnowledgeWorkerException("APVERO_KNOWLEDGE_WORKER_UNAVAILABLE", true);
        }
    }

    private URI workerEndpoint() {
        return properties.workerBaseUri().resolve("/internal/v1/documents/process");
    }

    private KnowledgeWorkerException workerProblem(byte[] body) {
        try {
            WorkerProblem problem = json.readValue(body, WorkerProblem.class);
            if (problem.code() == null || !problem.code().startsWith("WORKER_")
                    || problem.status() < 400 || problem.status() > 599) {
                return invalidResponse();
            }
            return new KnowledgeWorkerException(problem.code(), problem.retryable());
        } catch (RuntimeException exception) {
            return invalidResponse();
        }
    }

    private static void validate(ProcessedBatch result, UUID requestId, SourceRevisionRow revision) {
        if (result == null
                || !requestId.equals(result.requestId())
                || !revision.id().equals(result.sourceRevisionId())
                || !revision.contentDigest().equals(result.contentDigest())
                || !PROCESSING_PROFILE.equals(result.processingProfile())
                || !version(result.parserVersion())
                || !version(result.chunkerVersion())
                || result.documents() == null
                || result.documents().isEmpty()
                || result.documents().size() > 10_000
                || result.warnings() == null
                || result.warnings().size() > 1_000) {
            throw invalidResponse();
        }
        Set<Integer> documentOrdinals = new HashSet<>();
        int chunkCount = 0;
        for (ProcessedDocument document : result.documents()) {
            if (document == null
                    || document.ordinal() < 0
                    || !documentOrdinals.add(document.ordinal())
                    || !digest(document.contentDigest())
                    || document.title() != null
                            && (document.title().isEmpty() || codePoints(document.title()) > 1_000)
                    || document.chunks() == null
                    || document.chunks().size() > 100_000) {
                throw invalidResponse();
            }
            Set<Integer> chunkOrdinals = new HashSet<>();
            for (ProcessedChunk chunk : document.chunks()) {
                validateChunk(chunk, chunkOrdinals);
            }
            requireContiguous(chunkOrdinals, document.chunks().size());
            validateDocumentContent(document);
            chunkCount += document.chunks().size();
            if (chunkCount > 100_000) {
                throw invalidResponse();
            }
        }
        requireContiguous(documentOrdinals, result.documents().size());
        for (ProcessingWarning warning : result.warnings()) {
            if (warning == null
                    || !WARNING_CODES.contains(warning.code())
                    || warning.location() != null && codePoints(warning.location()) > 500) {
                throw invalidResponse();
            }
        }
    }

    private static void validateChunk(ProcessedChunk chunk, Set<Integer> ordinals) {
        if (chunk == null
                || chunk.ordinal() < 0
                || !ordinals.add(chunk.ordinal())
                || chunk.text() == null
                || codePoints(chunk.text()) < 1
                || codePoints(chunk.text()) > 20_000
                || !digest(chunk.contentDigest())
                || !sha256(chunk.text().getBytes(StandardCharsets.UTF_8)).equals(chunk.contentDigest())
                || chunk.startOffset() < 0
                || chunk.endOffset() <= chunk.startOffset()
                || chunk.endOffset() - chunk.startOffset() != codePoints(chunk.text())
                || chunk.anchors() == null) {
            throw invalidResponse();
        }
        SourceAnchors anchors = chunk.anchors();
        if (nonPositive(anchors.page())
                || nonPositive(anchors.paragraph())
                || anchors.heading() != null
                        && (anchors.heading().isEmpty() || codePoints(anchors.heading()) > 1_000)
                || (anchors.lineStart() == null) != (anchors.lineEnd() == null)
                || nonPositive(anchors.lineStart())
                || anchors.lineEnd() != null && anchors.lineEnd() < anchors.lineStart()) {
            throw invalidResponse();
        }
    }

    private static void validateDocumentContent(ProcessedDocument document) {
        if (document.chunks().isEmpty()) {
            throw invalidResponse();
        }
        List<ProcessedChunk> chunks = document.chunks().stream()
                .sorted(Comparator.comparingInt(ProcessedChunk::ordinal))
                .toList();
        StringBuilder reconstructed = new StringBuilder();
        String tail = "";
        int reconstructedLength = 0;
        for (ProcessedChunk chunk : chunks) {
            if (chunk.startOffset() > reconstructedLength || chunk.endOffset() <= reconstructedLength) {
                throw invalidResponse();
            }
            int overlap = reconstructedLength - chunk.startOffset();
            int tailLength = codePoints(tail);
            if (overlap > tailLength) {
                throw invalidResponse();
            }
            String existingOverlap = codePointSlice(tail, tailLength - overlap, tailLength);
            String returnedOverlap = codePointSlice(chunk.text(), 0, overlap);
            if (!existingOverlap.equals(returnedOverlap)) {
                throw invalidResponse();
            }
            String suffix = codePointSlice(chunk.text(), overlap, codePoints(chunk.text()));
            reconstructed.append(suffix);
            String tailCandidate = tail + suffix;
            int candidateLength = codePoints(tailCandidate);
            tail = codePointSlice(tailCandidate, Math.max(0, candidateLength - 20_000), candidateLength);
            reconstructedLength = chunk.endOffset();
        }
        if (!sha256(reconstructed.toString().getBytes(StandardCharsets.UTF_8))
                .equals(document.contentDigest())) {
            throw invalidResponse();
        }
    }

    private static byte[] multipart(
            String boundary, UUID requestId, SourceRevisionRow revision) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(revision.snapshotBytes().length + 1_024);
            field(output, boundary, "requestId", requestId.toString());
            field(output, boundary, "sourceRevisionId", revision.id().toString());
            field(output, boundary, "contentDigest", revision.contentDigest());
            field(output, boundary, "mediaType", revision.mediaType());
            field(output, boundary, "processingProfile", PROCESSING_PROFILE);
            output.write(("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"content\"; filename=\"snapshot.bin\"\r\n"
                            + "Content-Type: application/octet-stream\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.write(revision.snapshotBytes());
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void field(ByteArrayOutputStream output, String boundary, String name, String value)
            throws IOException {
        output.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                        + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        try (input) {
            byte[] body = input.readNBytes(maximumBytes + 1);
            if (body.length > maximumBytes) {
                throw invalidResponse();
            }
            return body;
        }
    }

    private static void requireContiguous(Set<Integer> ordinals, int size) {
        for (int ordinal = 0; ordinal < size; ordinal++) {
            if (!ordinals.contains(ordinal)) {
                throw invalidResponse();
            }
        }
    }

    private static boolean digest(String value) {
        return value != null && DIGEST.matcher(value).matches();
    }

    private static boolean version(String value) {
        return value != null && value.length() <= 120 && VERSION.matcher(value).matches();
    }

    private static boolean nonPositive(Integer value) {
        return value != null && value < 1;
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String codePointSlice(String value, int start, int end) {
        int startIndex = value.offsetByCodePoints(0, start);
        int endIndex = value.offsetByCodePoints(0, end);
        return value.substring(startIndex, endIndex);
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static KnowledgeWorkerException invalidResponse() {
        return new KnowledgeWorkerException("APVERO_KNOWLEDGE_WORKER_INVALID_RESPONSE", false);
    }
}
