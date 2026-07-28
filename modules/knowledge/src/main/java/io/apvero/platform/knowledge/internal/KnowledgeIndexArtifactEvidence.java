package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ChunkRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.DocumentRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import java.util.List;
import java.util.Objects;

record KnowledgeIndexArtifactEvidence(
        WorkspaceScope scope,
        BuildRow build,
        EmbeddingRouteSnapshot route,
        List<BuildRevisionRow> buildRevisions,
        List<SourceRevisionRow> sourceRevisions,
        List<DocumentRow> documents,
        List<ChunkRow> chunks,
        List<EntryRow> entries) {

    KnowledgeIndexArtifactEvidence {
        Objects.requireNonNull(scope, "APVERO_WORKSPACE_SCOPE_REQUIRED");
        Objects.requireNonNull(build, "APVERO_KNOWLEDGE_BUILD_REQUIRED");
        Objects.requireNonNull(route, "APVERO_EMBEDDING_ROUTE_REQUIRED");
        buildRevisions = List.copyOf(Objects.requireNonNull(
                buildRevisions, "APVERO_KNOWLEDGE_BUILD_REVISIONS_REQUIRED"));
        sourceRevisions = List.copyOf(Objects.requireNonNull(
                sourceRevisions, "APVERO_KNOWLEDGE_SOURCE_REVISIONS_REQUIRED"));
        documents = List.copyOf(Objects.requireNonNull(
                documents, "APVERO_KNOWLEDGE_DOCUMENTS_REQUIRED"));
        chunks = List.copyOf(Objects.requireNonNull(
                chunks, "APVERO_KNOWLEDGE_CHUNKS_REQUIRED"));
        entries = List.copyOf(Objects.requireNonNull(
                entries, "APVERO_KNOWLEDGE_ENTRIES_REQUIRED"));
    }
}
