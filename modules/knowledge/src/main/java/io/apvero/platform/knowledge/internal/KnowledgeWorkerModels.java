package io.apvero.platform.knowledge.internal;

import java.util.List;
import java.util.UUID;

final class KnowledgeWorkerModels {
    static final String PROCESSING_PROFILE = "apvero-default@1.0.0";

    private KnowledgeWorkerModels() {}

    record ProcessedBatch(
            UUID requestId,
            UUID sourceRevisionId,
            String contentDigest,
            String processingProfile,
            String parserVersion,
            String chunkerVersion,
            List<ProcessedDocument> documents,
            List<ProcessingWarning> warnings) {}

    record ProcessedDocument(
            int ordinal,
            String title,
            String contentDigest,
            List<ProcessedChunk> chunks) {}

    record ProcessedChunk(
            int ordinal,
            String text,
            String contentDigest,
            int startOffset,
            int endOffset,
            SourceAnchors anchors) {}

    record SourceAnchors(
            Integer page,
            String heading,
            Integer paragraph,
            Integer lineStart,
            Integer lineEnd) {}

    record ProcessingWarning(String code, String location) {}

    record WorkerProblem(
            String type,
            String title,
            int status,
            String code,
            boolean retryable,
            UUID requestId) {}
}
