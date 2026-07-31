package io.apvero.platform.runtime;

import java.math.BigDecimal;
import java.util.UUID;

public record RunRetrievalHit(
        String marker,
        int rank,
        BigDecimal score,
        UUID sourceId,
        UUID sourceRevisionId,
        UUID documentId,
        UUID chunkId,
        String contentDigest,
        String content,
        String sourceTitle,
        String sourceType,
        Integer page,
        String heading,
        Integer paragraph,
        Integer lineStart,
        Integer lineEnd,
        boolean citationValidated) {}
