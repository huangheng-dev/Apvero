package io.apvero.platform.runtime;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

public record GroundingContext(
        JsonNode evidence,
        int hitCount,
        long estimatedInputUnits) {

    public GroundingContext {
        Objects.requireNonNull(evidence, "evidence");
        if (!evidence.isArray()
                || hitCount < 1
                || evidence.size() != hitCount
                || estimatedInputUnits < 1) {
            throw new IllegalArgumentException("APVERO_RUNTIME_GROUNDING_CONTEXT_INVALID");
        }
        evidence = evidence.deepCopy();
    }

    @Override
    public JsonNode evidence() {
        return evidence.deepCopy();
    }
}
