package io.apvero.platform.runtime;

import tools.jackson.databind.JsonNode;

public record ProviderResult(
        JsonNode output,
        int promptTokens,
        int completionTokens,
        long costMicros) {}
