package io.apvero.platform.capability;

import java.util.Objects;

public record EmbeddingRouteProfile(
        int dimension,
        int maximumInputTokens,
        int maximumBatchSize,
        EmbeddingNormalization normalization) {
    public static final int MAXIMUM_VECTOR_DIMENSION = 16_000;
    public static final int MAXIMUM_INPUT_TOKENS = 2_000_000;
    public static final int MAXIMUM_BATCH_SIZE = 4_096;

    public EmbeddingRouteProfile {
        if (dimension < 1 || dimension > MAXIMUM_VECTOR_DIMENSION) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_DIMENSION_INVALID");
        }
        if (maximumInputTokens < 1 || maximumInputTokens > MAXIMUM_INPUT_TOKENS) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_INPUT_LIMIT_INVALID");
        }
        if (maximumBatchSize < 1 || maximumBatchSize > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_BATCH_LIMIT_INVALID");
        }
        Objects.requireNonNull(normalization, "APVERO_EMBEDDING_NORMALIZATION_REQUIRED");
    }
}
