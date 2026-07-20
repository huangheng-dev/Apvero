package io.apvero.platform.release.internal;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
final class ReleaseArtifactDigester {
    private final ObjectMapper json;

    ReleaseArtifactDigester(ObjectMapper json) {
        this.json = json;
    }

    String digest(JsonNode manifest) {
        try {
            byte[] canonical = json.writeValueAsBytes(canonicalize(manifest));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot calculate the release artifact digest.", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            node.properties().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> result.set(entry.getKey(), canonicalize(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return node;
    }
}
