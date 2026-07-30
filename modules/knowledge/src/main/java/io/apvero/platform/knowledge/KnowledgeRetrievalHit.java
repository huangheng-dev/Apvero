package io.apvero.platform.knowledge;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record KnowledgeRetrievalHit(
        int rank,
        BigDecimal score,
        UUID sourceId,
        UUID sourceRevisionId,
        UUID documentId,
        UUID chunkId,
        String contentDigest,
        String content,
        String sourceTitle,
        KnowledgeSource.Type sourceType,
        Integer page,
        String heading,
        Integer paragraph,
        Integer lineStart,
        Integer lineEnd) {
    private static final Pattern DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");

    public KnowledgeRetrievalHit {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(sourceRevisionId, "sourceRevisionId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(contentDigest, "contentDigest");
        Objects.requireNonNull(sourceType, "sourceType");
        if (rank < 1
                || score.compareTo(BigDecimal.ZERO) < 0
                || score.compareTo(BigDecimal.ONE) > 0
                || !DIGEST.matcher(contentDigest).matches()
                || exceedsCodePoints(content, 20_000)
                || exceedsCodePoints(sourceTitle, 500)
                || exceedsCodePoints(heading, 1_000)
                || (page != null && page < 1)
                || (paragraph != null && paragraph < 1)
                || (lineStart == null) != (lineEnd == null)
                || (lineStart != null && (lineStart < 1 || lineEnd < lineStart))) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_RETRIEVAL_HIT_INVALID");
        }
    }

    private static boolean exceedsCodePoints(String value, int maximum) {
        return value != null && value.codePointCount(0, value.length()) > maximum;
    }
}
