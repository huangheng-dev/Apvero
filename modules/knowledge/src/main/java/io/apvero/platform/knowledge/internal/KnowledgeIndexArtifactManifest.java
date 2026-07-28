package io.apvero.platform.knowledge.internal;

import java.util.List;
import java.util.UUID;

record KnowledgeIndexArtifactManifest(
        UUID tenantId,
        UUID workspaceId,
        UUID buildId,
        UUID indexId,
        UUID knowledgeBaseId,
        String requestedVersion,
        String sourceSetDigest,
        UUID embeddingRouteId,
        String embeddingRouteReference,
        int vectorDimension,
        int maximumInputTokens,
        int maximumBatchSize,
        String normalization,
        List<SourceManifest> sources,
        List<DocumentManifest> documents,
        List<ChunkManifest> chunks,
        List<EntryManifest> entries,
        String validationDigest,
        String artifactDigest) {

    KnowledgeIndexArtifactManifest {
        sources = List.copyOf(sources);
        documents = List.copyOf(documents);
        chunks = List.copyOf(chunks);
        entries = List.copyOf(entries);
    }

    int sourceCount() {
        return sources.size();
    }

    int chunkCount() {
        return chunks.size();
    }

    int documentCount() {
        return documents.size();
    }

    int entryCount() {
        return entries.size();
    }

    record SourceManifest(
            UUID buildRevisionId,
            UUID sourceId,
            UUID sourceRevisionId,
            int sourceSetOrdinal,
            String sourceContentDigest,
            String parserVersion,
            String chunkerVersion,
            int documentCount,
            int chunkCount) {}

    record DocumentManifest(
            UUID sourceId,
            UUID sourceRevisionId,
            UUID documentId,
            int documentOrdinal,
            String normalizedTextDigest,
            String parserVersion,
            String processingProfile) {}

    record ChunkManifest(
            UUID sourceId,
            UUID sourceRevisionId,
            UUID documentId,
            int documentOrdinal,
            UUID chunkId,
            int chunkOrdinal,
            int entryOrdinal,
            String contentDigest) {}

    record EntryManifest(
            UUID entryId,
            UUID sourceId,
            UUID sourceRevisionId,
            UUID documentId,
            UUID chunkId,
            int entryOrdinal,
            int batchOrdinal,
            String normalizedInputDigest,
            String vectorDigest) {}
}
