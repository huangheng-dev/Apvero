package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class KnowledgeEmbeddingEntryBatchWriter {
    private final KnowledgeIndexPersistenceRepository repository;

    KnowledgeEmbeddingEntryBatchWriter(KnowledgeIndexPersistenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    BatchWriteOutcome persist(
            WorkspaceScope scope,
            UUID buildId,
            List<EntryRow> expectedRows) {
        Objects.requireNonNull(scope, "APVERO_WORKSPACE_SCOPE_REQUIRED");
        Objects.requireNonNull(buildId, "APVERO_KNOWLEDGE_BUILD_ID_REQUIRED");
        List<EntryRow> rows = List.copyOf(Objects.requireNonNull(
                expectedRows, "APVERO_KNOWLEDGE_ENTRIES_REQUIRED"));
        validateBatch(scope, buildId, rows);
        repository.lockBuild(scope, buildId)
                .orElseThrow(() -> new IllegalArgumentException("APVERO_KNOWLEDGE_BUILD_NOT_FOUND"));

        List<EntryRow> allExisting = repository.listEntries(scope, buildId);
        Map<UUID, EntryRow> existingByChunk = new HashMap<>();
        for (EntryRow existing : allExisting) {
            existingByChunk.put(existing.chunkId(), existing);
        }
        List<EntryRow> selectedExisting = rows.stream()
                .map(row -> existingByChunk.get(row.chunkId()))
                .filter(Objects::nonNull)
                .toList();

        if (!selectedExisting.isEmpty()) {
            if (selectedExisting.size() != rows.size()) {
                throw new IllegalStateException("APVERO_KNOWLEDGE_ENTRY_BATCH_PARTIAL");
            }
            for (EntryRow expected : rows) {
                if (!sameEntry(existingByChunk.get(expected.chunkId()), expected)) {
                    throw new IllegalStateException("APVERO_KNOWLEDGE_ENTRY_BATCH_CONFLICT");
                }
            }
            return BatchWriteOutcome.ALREADY_PRESENT;
        }

        Set<Integer> occupiedOrdinals = allExisting.stream()
                .map(EntryRow::entryOrdinal)
                .collect(java.util.stream.Collectors.toSet());
        if (rows.stream().anyMatch(row -> occupiedOrdinals.contains(row.entryOrdinal()))) {
            throw new IllegalStateException("APVERO_KNOWLEDGE_ENTRY_BATCH_CONFLICT");
        }
        try {
            for (EntryRow row : rows) {
                repository.insertEntry(scope, row);
            }
        } catch (DataAccessException exception) {
            throw new IllegalStateException("APVERO_KNOWLEDGE_ENTRY_BATCH_CONFLICT");
        }
        return BatchWriteOutcome.INSERTED;
    }

    @Transactional
    BatchWriteOutcome persistUnderLease(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner,
            List<EntryRow> expectedRows) {
        Objects.requireNonNull(claim, "APVERO_KNOWLEDGE_BUILD_REQUIRED");
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_INDEX_BUILD_KERNEL_INPUT_INVALID");
        }
        repository.lockActiveBuildLease(
                        scope,
                        claim.id(),
                        claim.lockVersion(),
                        leaseOwner,
                        BuildStatus.EMBEDDING,
                        BuildStep.EMBEDDING)
                .orElseThrow(() -> new IllegalStateException(
                        "APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT"));
        return persist(scope, claim.id(), expectedRows);
    }

    private static void validateBatch(
            WorkspaceScope scope,
            UUID buildId,
            List<EntryRow> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_ENTRY_BATCH_EMPTY");
        }
        Set<UUID> ids = new HashSet<>();
        Set<UUID> chunks = new HashSet<>();
        Set<Integer> ordinals = new HashSet<>();
        Integer batchOrdinal = null;
        for (EntryRow row : rows) {
            if (row == null
                    || !scope.tenantId().equals(row.tenantId())
                    || !scope.workspaceId().equals(row.workspaceId())
                    || !buildId.equals(row.knowledgeIndexBuildId())) {
                throw new IllegalArgumentException("APVERO_KNOWLEDGE_SCOPE_MISMATCH");
            }
            if (!ids.add(row.id())
                    || !chunks.add(row.chunkId())
                    || !ordinals.add(row.entryOrdinal())) {
                throw new IllegalArgumentException("APVERO_KNOWLEDGE_ENTRY_BATCH_DUPLICATE");
            }
            if (batchOrdinal == null) {
                batchOrdinal = row.batchOrdinal();
            } else if (batchOrdinal != row.batchOrdinal()) {
                throw new IllegalArgumentException("APVERO_KNOWLEDGE_ENTRY_BATCH_ORDINAL_MISMATCH");
            }
        }
    }

    private static boolean sameEntry(EntryRow stored, EntryRow expected) {
        return stored != null
                && stored.id().equals(expected.id())
                && stored.tenantId().equals(expected.tenantId())
                && stored.workspaceId().equals(expected.workspaceId())
                && stored.knowledgeIndexBuildId().equals(expected.knowledgeIndexBuildId())
                && stored.knowledgeIndexId().equals(expected.knowledgeIndexId())
                && stored.knowledgeBaseId().equals(expected.knowledgeBaseId())
                && stored.sourceId().equals(expected.sourceId())
                && stored.sourceRevisionId().equals(expected.sourceRevisionId())
                && stored.documentId().equals(expected.documentId())
                && stored.chunkId().equals(expected.chunkId())
                && stored.entryOrdinal() == expected.entryOrdinal()
                && stored.embedding().equals(expected.embedding())
                && stored.vectorDimension() == expected.vectorDimension()
                && stored.vectorDigest().equals(expected.vectorDigest())
                && stored.normalizedInputDigest().equals(expected.normalizedInputDigest())
                && stored.batchOrdinal() == expected.batchOrdinal()
                && stored.embeddingRouteId().equals(expected.embeddingRouteId())
                && stored.embeddingRouteReference().equals(expected.embeddingRouteReference());
    }

    enum BatchWriteOutcome {
        INSERTED,
        ALREADY_PRESENT
    }
}
