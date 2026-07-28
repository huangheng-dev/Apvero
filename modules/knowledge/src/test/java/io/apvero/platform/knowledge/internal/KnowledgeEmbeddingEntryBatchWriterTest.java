package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingEntryBatchWriter.BatchWriteOutcome;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeEmbeddingEntryBatchWriterTest {
    private final KnowledgeIndexPersistenceRepository repository =
            mock(KnowledgeIndexPersistenceRepository.class);
    private final KnowledgeEmbeddingEntryBatchWriter writer =
            new KnowledgeEmbeddingEntryBatchWriter(repository);
    private final WorkspaceScope scope =
            new WorkspaceScope(UUID.randomUUID(), UUID.randomUUID());
    private final UUID buildId = UUID.randomUUID();

    @Test
    void insertsOnlyWhenTheWholeSelectedBatchIsMissing() {
        EntryRow first = row(UUID.randomUUID(), 0, 0, List.of(1f, 0f));
        EntryRow second = row(UUID.randomUUID(), 1, 0, List.of(0f, 1f));
        when(repository.lockBuild(scope, buildId))
                .thenReturn(java.util.Optional.of(mock(
                        KnowledgeIndexPersistenceRecords.BuildRow.class)));
        when(repository.listEntries(scope, buildId)).thenReturn(List.of());

        assertThat(writer.persist(scope, buildId, List.of(first, second)))
                .isEqualTo(BatchWriteOutcome.INSERTED);
        verify(repository).insertEntry(scope, first);
        verify(repository).insertEntry(scope, second);
    }

    @Test
    void acceptsAnExactlyEqualCompleteRetryWithoutWriting() {
        EntryRow expected = row(UUID.randomUUID(), 0, 0, List.of(1f, 0f));
        EntryRow stored = new EntryRow(
                expected.id(), expected.tenantId(), expected.workspaceId(),
                expected.knowledgeIndexBuildId(), expected.knowledgeIndexId(),
                expected.knowledgeBaseId(), expected.sourceId(), expected.sourceRevisionId(),
                expected.documentId(), expected.chunkId(), expected.entryOrdinal(),
                expected.embedding(), expected.vectorDimension(), expected.vectorDigest(),
                expected.normalizedInputDigest(), expected.batchOrdinal(),
                expected.embeddingRouteId(), expected.embeddingRouteReference(),
                expected.createdAt().plusSeconds(30));
        when(repository.lockBuild(scope, buildId))
                .thenReturn(java.util.Optional.of(mock(
                        KnowledgeIndexPersistenceRecords.BuildRow.class)));
        when(repository.listEntries(scope, buildId)).thenReturn(List.of(stored));

        assertThat(writer.persist(scope, buildId, List.of(expected)))
                .isEqualTo(BatchWriteOutcome.ALREADY_PRESENT);
        verify(repository, never()).insertEntry(scope, expected);
    }

    @Test
    void rejectsPartialAndDifferingExistingBatches() {
        EntryRow first = row(UUID.randomUUID(), 0, 0, List.of(1f, 0f));
        EntryRow second = row(UUID.randomUUID(), 1, 0, List.of(0f, 1f));
        when(repository.lockBuild(scope, buildId))
                .thenReturn(java.util.Optional.of(mock(
                        KnowledgeIndexPersistenceRecords.BuildRow.class)));
        when(repository.listEntries(scope, buildId)).thenReturn(List.of(first));

        assertThatThrownBy(() -> writer.persist(scope, buildId, List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_KNOWLEDGE_ENTRY_BATCH_PARTIAL");

        EntryRow changed = new EntryRow(
                first.id(), first.tenantId(), first.workspaceId(), first.knowledgeIndexBuildId(),
                first.knowledgeIndexId(), first.knowledgeBaseId(), first.sourceId(),
                first.sourceRevisionId(), first.documentId(), first.chunkId(),
                first.entryOrdinal(), List.of(0.5f, 0.5f), first.vectorDimension(),
                first.vectorDigest(), first.normalizedInputDigest(), first.batchOrdinal(),
                first.embeddingRouteId(), first.embeddingRouteReference(), first.createdAt());
        when(repository.listEntries(scope, buildId)).thenReturn(List.of(changed));
        assertThatThrownBy(() -> writer.persist(scope, buildId, List.of(first)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_KNOWLEDGE_ENTRY_BATCH_CONFLICT");
    }

    @Test
    void rejectsEntryPersistenceWhenTheDatabaseLeaseFenceDoesNotMatch() {
        EntryRow expected = row(UUID.randomUUID(), 0, 0, List.of(1f, 0f));
        BuildRow claim = mock(BuildRow.class);
        when(claim.id()).thenReturn(buildId);
        when(claim.lockVersion()).thenReturn(7L);
        when(repository.lockActiveBuildLease(
                scope,
                buildId,
                7L,
                "stale-worker",
                BuildStatus.EMBEDDING,
                BuildStep.EMBEDDING)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> writer.persistUnderLease(
                        scope, claim, "stale-worker", List.of(expected)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT");
        verify(repository, never()).insertEntry(scope, expected);
    }

    private EntryRow row(UUID chunkId, int entryOrdinal, int batchOrdinal, List<Float> vector) {
        return new EntryRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), buildId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), chunkId, entryOrdinal, vector, 2,
                digest('a'), digest('b'), batchOrdinal, UUID.randomUUID(), "embed@1",
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
