package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingExecutionQuote;
import io.apvero.platform.capability.EmbeddingExecutionResult;
import io.apvero.platform.capability.EmbeddingInputUnitEstimator;
import io.apvero.platform.capability.EmbeddingNormalization;
import io.apvero.platform.capability.EmbeddingReplayPolicy;
import io.apvero.platform.capability.EmbeddingRouteProfile;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.capability.EmbeddingUsageQuality;
import io.apvero.platform.capability.EmbeddingVectorOutput;
import io.apvero.platform.capability.ModelRouteCapability;
import io.apvero.platform.capability.ModelRouteStatus;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingEntryBatchWriter.BatchWriteOutcome;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ChunkRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.DocumentRow;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeEmbeddingBatchExecutorTest {
    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");
    private final KnowledgePersistenceRepository knowledge =
            mock(KnowledgePersistenceRepository.class);
    private final KnowledgeIndexPersistenceRepository indexes =
            mock(KnowledgeIndexPersistenceRepository.class);
    private final EmbeddingCapability embeddings = mock(EmbeddingCapability.class);
    private final EmbeddingInputUnitEstimator estimator =
            mock(EmbeddingInputUnitEstimator.class);
    private final KnowledgeEmbeddingEntryBatchWriter writer =
            mock(KnowledgeEmbeddingEntryBatchWriter.class);
    private final WorkspaceScope scope =
            new WorkspaceScope(UUID.randomUUID(), UUID.randomUUID());
    private final UUID indexId = UUID.randomUUID();
    private final UUID baseId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID revisionId = UUID.randomUUID();
    private final UUID routeId = UUID.randomUUID();
    private final UUID modelId = UUID.randomUUID();
    private final UUID buildId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID firstChunkId = UUID.randomUUID();
    private final UUID secondChunkId = UUID.randomUUID();
    private final BuildRow build = build();
    private final EmbeddingRouteSnapshot route = route();
    private KnowledgeEmbeddingBatchExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new KnowledgeEmbeddingBatchExecutor(
                knowledge,
                indexes,
                embeddings,
                estimator,
                writer,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(indexes.findBuild(scope, buildId)).thenReturn(java.util.Optional.of(build));
        when(embeddings.resolveEmbeddingRoute(scope.workspaceId(), routeId)).thenReturn(route);
        when(indexes.listBuildRevisions(scope, buildId)).thenReturn(List.of(revision()));
        when(knowledge.listDocuments(scope, revisionId)).thenReturn(List.of(document()));
        when(knowledge.listChunks(scope, revisionId)).thenReturn(List.of(
                chunk(secondChunkId, 1, "second"),
                chunk(firstChunkId, 0, "first")));
        when(indexes.listEntries(scope, buildId)).thenReturn(List.of());
        when(estimator.estimateUnits("first")).thenReturn(5L);
        when(estimator.estimateUnits("second")).thenReturn(6L);
        when(embeddings.quote(scope.workspaceId(), routeId, 11L))
                .thenReturn(new EmbeddingExecutionQuote(
                        route, 11, 3, "USD", EmbeddingReplayPolicy.SAFE_REPLAY));
    }

    @Test
    void preparesCanonicalPersistedInputsAndStableIdentity() {
        KnowledgeEmbeddingBatchRequest request = new KnowledgeEmbeddingBatchRequest(
                scope, buildId, 2, List.of(firstChunkId, secondChunkId));

        KnowledgeEmbeddingBatchPlan first = executor.prepare(request);
        KnowledgeEmbeddingBatchPlan repeated = executor.prepare(request);

        assertThat(first.state()).isEqualTo(KnowledgeEmbeddingBatchState.MISSING);
        assertThat(first.estimatedInputUnits()).isEqualTo(11);
        assertThat(first.orderedChunks())
                .extracting(KnowledgeEmbeddingBatchPlan.PlannedChunk::chunkId)
                .containsExactly(firstChunkId, secondChunkId);
        assertThat(first.orderedChunks())
                .extracting(KnowledgeEmbeddingBatchPlan.PlannedChunk::entryOrdinal)
                .containsExactly(0, 1);
        assertThat(first.idempotencyIdentity()).isEqualTo(repeated.idempotencyIdentity());
        assertThat(first.executionRequest().orderedInputs())
                .extracting(input -> input.itemId())
                .containsExactly(firstChunkId, secondChunkId);
    }

    @Test
    void rejectsNonCanonicalOrderBeforeQuoteOrDispatch() {
        KnowledgeEmbeddingBatchRequest request = new KnowledgeEmbeddingBatchRequest(
                scope, buildId, 0, List.of(secondChunkId, firstChunkId));

        assertThatThrownBy(() -> executor.prepare(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_EMBEDDING_CHUNK_ORDER_INVALID");
        verify(embeddings, never()).quote(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                anyLong());
    }

    @Test
    void rejectsOneOversizedChunkBeforeAggregateQuote() {
        when(estimator.estimateUnits("first")).thenReturn(8_193L);
        KnowledgeEmbeddingBatchRequest request = new KnowledgeEmbeddingBatchRequest(
                scope, buildId, 0, List.of(firstChunkId));

        assertThatThrownBy(() -> executor.prepare(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_EMBEDDING_CHUNK_OVERSIZED");
        verify(embeddings, never()).quote(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                anyLong());
    }

    @Test
    void detectsPartialExistingEntryBatchBeforeProviderWork() {
        when(indexes.listEntries(scope, buildId)).thenReturn(List.of(existing(firstChunkId, 0)));
        KnowledgeEmbeddingBatchRequest request = new KnowledgeEmbeddingBatchRequest(
                scope, buildId, 0, List.of(firstChunkId, secondChunkId));

        assertThatThrownBy(() -> executor.prepare(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_KNOWLEDGE_ENTRY_BATCH_PARTIAL");
    }

    @Test
    void mapsValidatedOutputsToExactLineageAndCanonicalVectorDigest() {
        KnowledgeEmbeddingBatchPlan plan = executor.prepare(new KnowledgeEmbeddingBatchRequest(
                scope, buildId, 2, List.of(firstChunkId, secondChunkId)));
        EmbeddingExecutionResult result = new EmbeddingExecutionResult(
                routeId,
                "embedding-route@1",
                modelId,
                "embedding-model",
                3,
                plan.idempotencyIdentity(),
                List.of(
                        new EmbeddingVectorOutput(
                                firstChunkId, rawDigest("first"), List.of(1f, 0f, 0f)),
                        new EmbeddingVectorOutput(
                                secondChunkId, rawDigest("second"), List.of(0f, 1f, 0f))),
                11L,
                EmbeddingUsageQuality.ESTIMATED,
                3,
                "USD",
                null,
                4);
        when(writer.persist(
                        org.mockito.ArgumentMatchers.eq(scope),
                        org.mockito.ArgumentMatchers.eq(buildId),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(BatchWriteOutcome.INSERTED);

        assertThat(executor.persist(plan, result)).isEqualTo(BatchWriteOutcome.INSERTED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EntryRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(writer).persist(
                org.mockito.ArgumentMatchers.eq(scope),
                org.mockito.ArgumentMatchers.eq(buildId),
                rows.capture());
        assertThat(rows.getValue())
                .extracting(EntryRow::entryOrdinal)
                .containsExactly(0, 1);
        assertThat(rows.getValue())
                .extracting(EntryRow::normalizedInputDigest)
                .containsExactly(digest("first"), digest("second"));
        assertThat(rows.getValue())
                .extracting(EntryRow::batchOrdinal)
                .containsOnly(2);
        assertThat(rows.getValue())
                .extracting(EntryRow::vectorDigest)
                .allMatch(value -> value.matches("^sha256:[a-f0-9]{64}$"));
        when(indexes.listEntries(scope, buildId)).thenReturn(rows.getValue());
        assertThat(executor.prepare(new KnowledgeEmbeddingBatchRequest(
                        scope, buildId, 2, List.of(firstChunkId, secondChunkId))).state())
                .isEqualTo(KnowledgeEmbeddingBatchState.COMPLETE_EQUAL);
    }

    private BuildRow build() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new BuildRow(
                buildId, scope.tenantId(), scope.workspaceId(), indexId, baseId, "1.0.0",
                routeId, "embedding-route@1", 3, 8_192, 64, "L2",
                hexDigest('a'), hexDigest('b'), 1, 2,
                BuildStatus.EMBEDDING, BuildStep.EMBEDDING, 1, 3, false,
                null, "lease", now.plusMinutes(5), 1, false,
                0, 0, null, null, null, null, null, null, false, "{}",
                now, null, now, now);
    }

    private EmbeddingRouteSnapshot route() {
        return new EmbeddingRouteSnapshot(
                routeId, scope.tenantId(), scope.workspaceId(), "embedding-route", 1,
                modelId, ModelRouteCapability.EMBEDDING, ModelRouteStatus.PUBLISHED, 30_000,
                new EmbeddingRouteProfile(3, 8_192, 64, EmbeddingNormalization.L2),
                true, "READY", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private BuildRevisionRow revision() {
        return new BuildRevisionRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), buildId,
                indexId, baseId, sourceId, revisionId, hexDigest('c'),
                "parser@1", "chunker@1", 0, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private DocumentRow document() {
        return new DocumentRow(
                documentId, scope.tenantId(), scope.workspaceId(), revisionId, 0,
                "Document", hexDigest('d'), "parser@1", "profile@1",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private ChunkRow chunk(UUID chunkId, int ordinal, String text) {
        return new ChunkRow(
                chunkId, scope.tenantId(), scope.workspaceId(), revisionId, documentId,
                ordinal, text, digest(text), ordinal * 10, ordinal * 10 + text.length(),
                null, null, null, null, null, "chunker@1",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private EntryRow existing(UUID chunkId, int ordinal) {
        return new EntryRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), buildId,
                indexId, baseId, sourceId, revisionId, documentId, chunkId, ordinal,
                List.of(1f, 0f, 0f), 3, hexDigest('e'),
                chunkId.equals(firstChunkId) ? digest("first") : digest("second"),
                0, routeId, "embedding-route@1", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private static String digest(String value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String rawDigest(String value) {
        return digest(value).substring("sha256:".length());
    }

    private static String hexDigest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
