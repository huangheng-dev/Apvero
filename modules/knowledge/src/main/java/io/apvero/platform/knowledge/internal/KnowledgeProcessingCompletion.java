package io.apvero.platform.knowledge.internal;

import static io.apvero.platform.knowledge.KnowledgeException.Category.NOT_FOUND;
import static io.apvero.platform.knowledge.KnowledgeException.Category.UNPROCESSABLE;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ChunkRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.DocumentRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedBatch;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedChunk;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedDocument;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class KnowledgeProcessingCompletion {
    private final KnowledgePersistenceRepository repository;

    KnowledgeProcessingCompletion(KnowledgePersistenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    void complete(WorkspaceScope scope, ProcessedBatch batch) {
        SourceRevisionRow revision = repository.lockRevision(scope, batch.sourceRevisionId())
                .orElseThrow(() -> new KnowledgeException("APVERO_KNOWLEDGE_REVISION_NOT_FOUND", NOT_FOUND));
        requireIdentity(revision, batch);

        List<DocumentRow> existingDocuments = repository.listDocuments(scope, revision.id());
        List<ChunkRow> existingChunks = repository.listChunks(scope, revision.id());
        if (!existingDocuments.isEmpty() || !existingChunks.isEmpty()) {
            requireSameOutput(revision, batch, existingDocuments, existingChunks);
            return;
        }

        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        for (ProcessedDocument document : orderedDocuments(batch)) {
            UUID documentId = documentId(revision.id(), document.ordinal());
            repository.insertDocument(scope, documentRow(scope, revision, batch, document, documentId, createdAt));
            for (ProcessedChunk chunk : orderedChunks(document)) {
                repository.insertChunk(scope, chunkRow(
                        scope, revision, batch, documentId, chunk, createdAt));
            }
        }
    }

    private static void requireIdentity(SourceRevisionRow revision, ProcessedBatch batch) {
        if (!revision.id().equals(batch.sourceRevisionId())
                || !revision.contentDigest().equals(batch.contentDigest())
                || !KnowledgeWorkerModels.PROCESSING_PROFILE.equals(batch.processingProfile())) {
            throw nonDeterministic();
        }
    }

    private static void requireSameOutput(
            SourceRevisionRow revision,
            ProcessedBatch batch,
            List<DocumentRow> existingDocuments,
            List<ChunkRow> existingChunks) {
        List<ProcessedDocument> documents = orderedDocuments(batch);
        if (existingDocuments.size() != documents.size()) {
            throw nonDeterministic();
        }
        Map<UUID, List<ChunkRow>> chunksByDocument = new HashMap<>();
        for (ChunkRow chunk : existingChunks) {
            chunksByDocument.computeIfAbsent(chunk.documentId(), ignored -> new java.util.ArrayList<>()).add(chunk);
        }
        int expectedChunkCount = 0;
        for (int index = 0; index < documents.size(); index++) {
            ProcessedDocument actual = documents.get(index);
            DocumentRow stored = existingDocuments.get(index);
            UUID expectedId = documentId(revision.id(), actual.ordinal());
            if (!sameDocument(stored, batch, actual, expectedId)) {
                throw nonDeterministic();
            }
            List<ChunkRow> storedChunks = chunksByDocument.getOrDefault(expectedId, List.of()).stream()
                    .sorted(Comparator.comparingInt(ChunkRow::ordinal))
                    .toList();
            List<ProcessedChunk> actualChunks = orderedChunks(actual);
            expectedChunkCount += actualChunks.size();
            if (storedChunks.size() != actualChunks.size()) {
                throw nonDeterministic();
            }
            for (int chunkIndex = 0; chunkIndex < actualChunks.size(); chunkIndex++) {
                if (!sameChunk(storedChunks.get(chunkIndex), batch, expectedId, actualChunks.get(chunkIndex))) {
                    throw nonDeterministic();
                }
            }
        }
        if (expectedChunkCount != existingChunks.size()) {
            throw nonDeterministic();
        }
    }

    private static boolean sameDocument(
            DocumentRow stored, ProcessedBatch batch, ProcessedDocument actual, UUID expectedId) {
        return stored.id().equals(expectedId)
                && stored.ordinal() == actual.ordinal()
                && java.util.Objects.equals(stored.title(), actual.title())
                && stored.normalizedTextDigest().equals(actual.contentDigest())
                && stored.parserVersion().equals(batch.parserVersion())
                && stored.processingProfile().equals(batch.processingProfile());
    }

    private static boolean sameChunk(
            ChunkRow stored, ProcessedBatch batch, UUID documentId, ProcessedChunk actual) {
        return stored.id().equals(chunkId(documentId, actual.ordinal()))
                && stored.ordinal() == actual.ordinal()
                && stored.text().equals(actual.text())
                && stored.contentDigest().equals(actual.contentDigest())
                && stored.startOffset() == actual.startOffset()
                && stored.endOffset() == actual.endOffset()
                && java.util.Objects.equals(stored.pageNumber(), actual.anchors().page())
                && java.util.Objects.equals(stored.heading(), actual.anchors().heading())
                && java.util.Objects.equals(stored.paragraphNumber(), actual.anchors().paragraph())
                && java.util.Objects.equals(stored.lineStart(), actual.anchors().lineStart())
                && java.util.Objects.equals(stored.lineEnd(), actual.anchors().lineEnd())
                && stored.chunkerVersion().equals(batch.chunkerVersion());
    }

    private static DocumentRow documentRow(
            WorkspaceScope scope,
            SourceRevisionRow revision,
            ProcessedBatch batch,
            ProcessedDocument document,
            UUID documentId,
            OffsetDateTime createdAt) {
        return new DocumentRow(
                documentId, scope.tenantId(), scope.workspaceId(), revision.id(), document.ordinal(),
                document.title(), document.contentDigest(), batch.parserVersion(), batch.processingProfile(), createdAt);
    }

    private static ChunkRow chunkRow(
            WorkspaceScope scope,
            SourceRevisionRow revision,
            ProcessedBatch batch,
            UUID documentId,
            ProcessedChunk chunk,
            OffsetDateTime createdAt) {
        return new ChunkRow(
                chunkId(documentId, chunk.ordinal()), scope.tenantId(), scope.workspaceId(), revision.id(),
                documentId, chunk.ordinal(), chunk.text(), chunk.contentDigest(), chunk.startOffset(),
                chunk.endOffset(), chunk.anchors().page(), chunk.anchors().heading(), chunk.anchors().paragraph(),
                chunk.anchors().lineStart(), chunk.anchors().lineEnd(), batch.chunkerVersion(), createdAt);
    }

    private static List<ProcessedDocument> orderedDocuments(ProcessedBatch batch) {
        return batch.documents().stream()
                .sorted(Comparator.comparingInt(ProcessedDocument::ordinal))
                .toList();
    }

    private static List<ProcessedChunk> orderedChunks(ProcessedDocument document) {
        return document.chunks().stream()
                .sorted(Comparator.comparingInt(ProcessedChunk::ordinal))
                .toList();
    }

    private static UUID documentId(UUID revisionId, int ordinal) {
        return stableId("apvero:knowledge-document:" + revisionId + ':' + ordinal);
    }

    private static UUID chunkId(UUID documentId, int ordinal) {
        return stableId("apvero:knowledge-chunk:" + documentId + ':' + ordinal);
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static KnowledgeException nonDeterministic() {
        return new KnowledgeException("APVERO_KNOWLEDGE_NON_DETERMINISTIC_OUTPUT", UNPROCESSABLE);
    }
}
