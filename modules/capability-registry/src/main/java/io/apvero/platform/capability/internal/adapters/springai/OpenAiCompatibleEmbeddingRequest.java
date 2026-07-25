package io.apvero.platform.capability.internal.adapters.springai;

import io.apvero.platform.capability.EmbeddingRouteProfile;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OpenAiCompatibleEmbeddingRequest(
        UUID workspaceId,
        UUID secretReferenceId,
        String baseUrl,
        String modelKey,
        int dimension,
        long timeoutMs,
        List<String> orderedInputs) {

    public OpenAiCompatibleEmbeddingRequest {
        Objects.requireNonNull(workspaceId, "APVERO_WORKSPACE_ID_REQUIRED");
        Objects.requireNonNull(secretReferenceId, "APVERO_SECRET_REFERENCE_ID_REQUIRED");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_BASE_URL_REQUIRED");
        }
        if (modelKey == null || modelKey.isBlank() || modelKey.length() > 200) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_MODEL_KEY_INVALID");
        }
        if (dimension < 1 || dimension > EmbeddingRouteProfile.MAXIMUM_VECTOR_DIMENSION) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_DIMENSION_INVALID");
        }
        if (timeoutMs < 1_000 || timeoutMs > 120_000) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_TIMEOUT_INVALID");
        }
        orderedInputs = List.copyOf(Objects.requireNonNull(
                orderedInputs, "APVERO_EMBEDDING_INPUTS_REQUIRED"));
        if (orderedInputs.isEmpty() || orderedInputs.size() > EmbeddingRouteProfile.MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_BATCH_SIZE_INVALID");
        }
        if (orderedInputs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_TEXT_REQUIRED");
        }
        baseUrl = baseUrl.trim();
        modelKey = modelKey.trim();
    }
}
