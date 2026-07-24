package io.apvero.platform.capability;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EmbeddingVectorOutput(UUID itemId, String contentDigest, List<Float> vector) {
    public EmbeddingVectorOutput {
        Objects.requireNonNull(itemId, "APVERO_EMBEDDING_ITEM_ID_REQUIRED");
        if (contentDigest == null || !contentDigest.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_CONTENT_DIGEST_INVALID");
        }
        vector = List.copyOf(Objects.requireNonNull(vector, "APVERO_EMBEDDING_VECTOR_REQUIRED"));
        if (vector.isEmpty() || vector.size() > EmbeddingRouteProfile.MAXIMUM_VECTOR_DIMENSION) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_VECTOR_DIMENSION_INVALID");
        }
        double squaredNorm = 0;
        for (Float value : vector) {
            if (value == null || !Float.isFinite(value)) {
                throw new IllegalArgumentException("APVERO_EMBEDDING_VECTOR_NON_FINITE");
            }
            squaredNorm += (double) value * value;
        }
        if (!(squaredNorm > 0) || !Double.isFinite(squaredNorm)) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_VECTOR_ZERO_NORM");
        }
    }
}
