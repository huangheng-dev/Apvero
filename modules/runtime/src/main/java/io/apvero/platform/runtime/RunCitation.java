package io.apvero.platform.runtime;

import java.math.BigDecimal;
import java.util.UUID;

public record RunCitation(
        String marker,
        String indexVersion,
        UUID sourceId,
        UUID sourceRevisionId,
        UUID documentId,
        UUID chunkId,
        String contentDigest,
        int rank,
        BigDecimal score,
        String sourceTitle,
        String sourceType,
        Integer page,
        String heading,
        Integer paragraph,
        Integer lineStart,
        Integer lineEnd,
        String locator) {}
