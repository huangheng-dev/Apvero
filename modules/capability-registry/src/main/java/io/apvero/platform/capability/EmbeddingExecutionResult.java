package io.apvero.platform.capability;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record EmbeddingExecutionResult(
        UUID routeId,
        String routeReference,
        UUID modelId,
        String modelKey,
        int dimension,
        String executionIdentity,
        List<EmbeddingVectorOutput> orderedOutputs,
        Long actualInputUnits,
        EmbeddingUsageQuality usageQuality,
        long costMicros,
        String currency,
        String providerRequestIdentity,
        long latencyMillis) {

    public EmbeddingExecutionResult {
        Objects.requireNonNull(routeId, "APVERO_EMBEDDING_ROUTE_ID_REQUIRED");
        Objects.requireNonNull(modelId, "APVERO_MODEL_ID_REQUIRED");
        EmbeddingExecutionRequest.requireExactRouteReference(routeReference);
        if (modelKey == null || modelKey.isBlank() || modelKey.length() > 200) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_MODEL_KEY_INVALID");
        }
        if (dimension < 1 || dimension > EmbeddingRouteProfile.MAXIMUM_VECTOR_DIMENSION) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_DIMENSION_INVALID");
        }
        if (executionIdentity == null || executionIdentity.isBlank() || executionIdentity.length() > 200) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_EXECUTION_IDENTITY_INVALID");
        }
        orderedOutputs = List.copyOf(Objects.requireNonNull(
                orderedOutputs, "APVERO_EMBEDDING_OUTPUTS_REQUIRED"));
        if (orderedOutputs.isEmpty()) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_OUTPUTS_EMPTY");
        }
        if (orderedOutputs.size() > EmbeddingRouteProfile.MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_BATCH_SIZE_INVALID");
        }
        Set<UUID> itemIds = new HashSet<>();
        for (EmbeddingVectorOutput output : orderedOutputs) {
            if (!itemIds.add(output.itemId())) {
                throw new IllegalArgumentException("APVERO_EMBEDDING_OUTPUT_DUPLICATE");
            }
            if (output.vector().size() != dimension) {
                throw new IllegalArgumentException("APVERO_EMBEDDING_OUTPUT_DIMENSION_MISMATCH");
            }
        }
        Objects.requireNonNull(usageQuality, "APVERO_EMBEDDING_USAGE_QUALITY_REQUIRED");
        if ((actualInputUnits == null) != (usageQuality == EmbeddingUsageQuality.UNAVAILABLE)) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_USAGE_INCONSISTENT");
        }
        if (actualInputUnits != null && actualInputUnits < 0) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_USAGE_INVALID");
        }
        if (costMicros < 0) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_COST_INVALID");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_CURRENCY_INVALID");
        }
        if (providerRequestIdentity != null
                && (providerRequestIdentity.isBlank() || providerRequestIdentity.length() > 200)) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_PROVIDER_REQUEST_IDENTITY_INVALID");
        }
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_LATENCY_INVALID");
        }
        routeReference = routeReference.trim();
        modelKey = modelKey.trim();
        executionIdentity = executionIdentity.trim();
    }

    public void validateAgainst(EmbeddingExecutionRequest request) {
        Objects.requireNonNull(request, "APVERO_EMBEDDING_REQUEST_REQUIRED");
        if (!routeReference.equals(request.exactRouteReference())
                || !executionIdentity.equals(request.executionIdentity())
                || orderedOutputs.size() != request.orderedInputs().size()) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_OUTPUT_MAPPING_INVALID");
        }
        for (int index = 0; index < orderedOutputs.size(); index++) {
            EmbeddingInput input = request.orderedInputs().get(index);
            EmbeddingVectorOutput output = orderedOutputs.get(index);
            if (!input.itemId().equals(output.itemId())
                    || !input.contentDigest().equals(output.contentDigest())) {
                throw new IllegalArgumentException("APVERO_EMBEDDING_OUTPUT_MAPPING_INVALID");
            }
        }
    }
}
