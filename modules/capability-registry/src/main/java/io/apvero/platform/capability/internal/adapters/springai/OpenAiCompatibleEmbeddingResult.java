package io.apvero.platform.capability.internal.adapters.springai;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record OpenAiCompatibleEmbeddingResult(
        List<float[]> orderedVectors,
        Long actualInputUnits,
        String providerRequestIdentity,
        long latencyMillis) {

    public OpenAiCompatibleEmbeddingResult {
        Objects.requireNonNull(orderedVectors, "APVERO_EMBEDDING_OUTPUTS_REQUIRED");
        orderedVectors = orderedVectors.stream()
                .map(vector -> Arrays.copyOf(
                        Objects.requireNonNull(vector, "APVERO_EMBEDDING_VECTOR_REQUIRED"),
                        vector.length))
                .toList();
        if (actualInputUnits != null && actualInputUnits < 0) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_USAGE_INVALID");
        }
        if (providerRequestIdentity != null
                && (providerRequestIdentity.isBlank() || providerRequestIdentity.length() > 200)) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_PROVIDER_REQUEST_IDENTITY_INVALID");
        }
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_LATENCY_INVALID");
        }
    }

    @Override
    public List<float[]> orderedVectors() {
        return orderedVectors.stream().map(float[]::clone).toList();
    }
}
