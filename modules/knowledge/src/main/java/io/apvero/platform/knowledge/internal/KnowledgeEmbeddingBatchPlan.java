package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingExecutionQuote;
import io.apvero.platform.capability.EmbeddingExecutionRequest;
import io.apvero.platform.capability.EmbeddingInput;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

record KnowledgeEmbeddingBatchPlan(
        WorkspaceScope scope,
        BuildRow build,
        int batchOrdinal,
        String idempotencyIdentity,
        long estimatedInputUnits,
        EmbeddingExecutionQuote quote,
        KnowledgeEmbeddingBatchState state,
        List<PlannedChunk> orderedChunks) {

    KnowledgeEmbeddingBatchPlan {
        Objects.requireNonNull(scope, "APVERO_WORKSPACE_SCOPE_REQUIRED");
        Objects.requireNonNull(build, "APVERO_KNOWLEDGE_BUILD_REQUIRED");
        Objects.requireNonNull(quote, "APVERO_EMBEDDING_QUOTE_REQUIRED");
        Objects.requireNonNull(state, "APVERO_KNOWLEDGE_EMBEDDING_BATCH_STATE_REQUIRED");
        orderedChunks = List.copyOf(Objects.requireNonNull(
                orderedChunks, "APVERO_KNOWLEDGE_EMBEDDING_CHUNKS_REQUIRED"));
        if (idempotencyIdentity == null || !idempotencyIdentity.matches(
                "^knowledge-embedding:[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_EMBEDDING_IDEMPOTENCY_INVALID");
        }
        if (estimatedInputUnits < 1 || orderedChunks.isEmpty()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_EMBEDDING_BATCH_INVALID");
        }
    }

    EmbeddingExecutionRequest executionRequest() {
        return new EmbeddingExecutionRequest(
                scope.workspaceId(),
                build.embeddingRouteReference(),
                idempotencyIdentity,
                orderedChunks.stream()
                        .map(chunk -> new EmbeddingInput(
                                chunk.chunkId(),
                                chunk.contentDigest().substring("sha256:".length()),
                                chunk.text()))
                        .toList());
    }

    record PlannedChunk(
            UUID sourceId,
            UUID sourceRevisionId,
            UUID documentId,
            UUID chunkId,
            int entryOrdinal,
            String text,
            String contentDigest) {

        PlannedChunk {
            Objects.requireNonNull(sourceId, "APVERO_KNOWLEDGE_SOURCE_ID_REQUIRED");
            Objects.requireNonNull(sourceRevisionId, "APVERO_KNOWLEDGE_REVISION_ID_REQUIRED");
            Objects.requireNonNull(documentId, "APVERO_KNOWLEDGE_DOCUMENT_ID_REQUIRED");
            Objects.requireNonNull(chunkId, "APVERO_KNOWLEDGE_CHUNK_ID_REQUIRED");
            if (entryOrdinal < 0) {
                throw new IllegalArgumentException("APVERO_KNOWLEDGE_ENTRY_ORDINAL_INVALID");
            }
            if (text == null || text.isEmpty()
                    || contentDigest == null
                    || !contentDigest.matches("^sha256:[a-f0-9]{64}$")) {
                throw new IllegalArgumentException("APVERO_KNOWLEDGE_CHUNK_INVALID");
            }
        }
    }
}
