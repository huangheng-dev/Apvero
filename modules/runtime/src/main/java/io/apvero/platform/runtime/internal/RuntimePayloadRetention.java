package io.apvero.platform.runtime.internal;

import io.apvero.platform.capability.ExecutionRetentionDecision;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class RuntimePayloadRetention {
    private final ObjectMapper json;

    RuntimePayloadRetention(ObjectMapper json) {
        this.json = json;
    }

    JsonNode apply(JsonNode payload, ExecutionRetentionDecision retention) {
        if (!retention.retainPayloads()) {
            return json.createObjectNode().put("retained", false);
        }
        return retention.maskSensitiveFields() ? mask(payload) : payload.deepCopy();
    }

    private JsonNode mask(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            node.properties().forEach(entry -> result.set(
                    entry.getKey(),
                    isSensitive(entry.getKey())
                            ? json.getNodeFactory().textNode("***")
                            : mask(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.valueStream().forEach(value -> result.add(mask(value)));
            return result;
        }
        return node.deepCopy();
    }

    private static boolean isSensitive(String key) {
        String normalized = key.replace("_", "")
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("authorization");
    }
}
