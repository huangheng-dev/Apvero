package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

record KnowledgeEmbeddingBatchRequest(
        WorkspaceScope scope,
        UUID buildId,
        int batchOrdinal,
        List<UUID> orderedChunkIds) {

    KnowledgeEmbeddingBatchRequest {
        Objects.requireNonNull(scope, "APVERO_WORKSPACE_SCOPE_REQUIRED");
        Objects.requireNonNull(buildId, "APVERO_KNOWLEDGE_BUILD_ID_REQUIRED");
        if (batchOrdinal < 0) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_EMBEDDING_BATCH_ORDINAL_INVALID");
        }
        orderedChunkIds = List.copyOf(Objects.requireNonNull(
                orderedChunkIds, "APVERO_KNOWLEDGE_EMBEDDING_CHUNKS_REQUIRED"));
        if (orderedChunkIds.isEmpty()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_EMBEDDING_BATCH_EMPTY");
        }
        if (new HashSet<>(orderedChunkIds).size() != orderedChunkIds.size()
                || orderedChunkIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_EMBEDDING_CHUNK_DUPLICATE");
        }
    }
}
