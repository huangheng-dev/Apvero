package io.apvero.platform.runtime.internal;

import io.apvero.platform.runtime.RunRetrievalEvidence;
import io.apvero.platform.runtime.RunRetrievalHit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class GroundedAnswerValidator {
    private static final Pattern MARKER = Pattern.compile("^\\[K[1-9][0-9]*]$");
    private static final int MAXIMUM_ANSWER_LENGTH = 200_000;
    private static final int MAXIMUM_CITATIONS = 128;
    private static final Set<String> ALLOWED_FIELDS =
            Set.of("schemaVersion", "status", "answer", "citationMarkers");

    private final ObjectMapper json;

    GroundedAnswerValidator(ObjectMapper json) {
        this.json = json;
    }

    ValidatedGroundedAnswer validate(
            JsonNode providerOutput, RunRetrievalEvidence evidence) {
        JsonNode draft = parseDraft(providerOutput);
        requireShape(draft);
        String answer = text(draft, "answer");
        if (answer.isBlank() || answer.length() > MAXIMUM_ANSWER_LENGTH) {
            throw invalidOutput();
        }
        ArrayNode requested = (ArrayNode) draft.get("citationMarkers");
        if (requested.isEmpty() || requested.size() > MAXIMUM_CITATIONS) {
            throw citationFailure();
        }
        Map<String, EvidenceHit> available = available(evidence);
        LinkedHashSet<String> markers = new LinkedHashSet<>();
        for (JsonNode markerNode : requested) {
            if (!markerNode.isString()) {
                throw citationFailure();
            }
            String marker = markerNode.stringValue();
            if (!MARKER.matcher(marker).matches()
                    || !markers.add(marker)
                    || !available.containsKey(marker)) {
                throw citationFailure();
            }
        }
        ObjectNode output = json.createObjectNode();
        output.put("schemaVersion", "1.0");
        output.put("status", "GROUNDED");
        output.put("answer", answer);
        ArrayNode citations = output.putArray("citations");
        for (String marker : markers) {
            citations.add(citation(marker, available.get(marker)));
        }
        return new ValidatedGroundedAnswer(output, markers);
    }

    private JsonNode parseDraft(JsonNode providerOutput) {
        JsonNode message = providerOutput == null ? null : providerOutput.get("message");
        if (message == null || !message.isString()) {
            throw invalidOutput();
        }
        try {
            return json.readTree(message.stringValue());
        } catch (JacksonException failure) {
            throw invalidOutput();
        }
    }

    private static void requireShape(JsonNode draft) {
        if (draft == null
                || !draft.isObject()
                || draft.size() != ALLOWED_FIELDS.size()
                || !ALLOWED_FIELDS.stream().allMatch(draft::has)
                || !"1.0".equals(text(draft, "schemaVersion"))
                || !"GROUNDED".equals(text(draft, "status"))
                || !draft.get("citationMarkers").isArray()) {
            throw invalidOutput();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw invalidOutput();
        }
        return value.stringValue();
    }

    private static Map<String, EvidenceHit> available(RunRetrievalEvidence evidence) {
        Map<String, EvidenceHit> available = new LinkedHashMap<>();
        evidence.retrievals().forEach(retrieval -> retrieval.hits().forEach(hit -> {
            if (available.put(
                            hit.marker(),
                            new EvidenceHit(
                                    retrieval.indexVersionReference(), hit))
                    != null) {
                throw citationFailure();
            }
        }));
        return available;
    }

    private ObjectNode citation(String marker, EvidenceHit evidence) {
        RunRetrievalHit hit = evidence.hit();
        ObjectNode citation = json.createObjectNode();
        citation.put("marker", marker);
        citation.put("indexVersion", evidence.indexVersion());
        citation.put("sourceId", hit.sourceId().toString());
        citation.put("sourceRevisionId", hit.sourceRevisionId().toString());
        citation.put("documentId", hit.documentId().toString());
        citation.put("chunkId", hit.chunkId().toString());
        citation.put("contentDigest", hit.contentDigest());
        citation.put("rank", hit.rank());
        citation.put("score", hit.score());
        if (hit.sourceTitle() != null) citation.put("sourceTitle", hit.sourceTitle());
        citation.put("sourceType", hit.sourceType());
        if (hit.page() != null) citation.put("page", hit.page());
        if (hit.heading() != null) citation.put("heading", hit.heading());
        if (hit.paragraph() != null) citation.put("paragraph", hit.paragraph());
        if (hit.lineStart() != null) citation.put("lineStart", hit.lineStart());
        if (hit.lineEnd() != null) citation.put("lineEnd", hit.lineEnd());
        return citation;
    }

    private static GroundedOutputValidationException invalidOutput() {
        return new GroundedOutputValidationException(
                "APVERO_GROUNDED_OUTPUT_INVALID");
    }

    private static GroundedOutputValidationException citationFailure() {
        return new GroundedOutputValidationException(
                "APVERO_CITATION_VALIDATION_FAILED");
    }

    private record EvidenceHit(String indexVersion, RunRetrievalHit hit) {}
}
