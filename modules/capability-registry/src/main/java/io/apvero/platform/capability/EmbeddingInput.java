package io.apvero.platform.capability;

import java.util.Objects;
import java.util.UUID;

public record EmbeddingInput(UUID itemId, String contentDigest, String boundedText) {
    public EmbeddingInput {
        Objects.requireNonNull(itemId, "APVERO_EMBEDDING_ITEM_ID_REQUIRED");
        if (contentDigest == null || !contentDigest.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_CONTENT_DIGEST_INVALID");
        }
        if (boundedText == null || boundedText.isBlank()) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_INPUT_EMPTY");
        }
    }
}
