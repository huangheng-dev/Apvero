package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingExecutionQuote;
import io.apvero.platform.capability.EmbeddingExecutionResult;
import io.apvero.platform.capability.EmbeddingInputUnitEstimator;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.capability.EmbeddingVectorOutput;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeEmbeddingBatchPlan.PlannedChunk;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ChunkRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.DocumentRow;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class KnowledgeEmbeddingBatchExecutor {
    private static final String SHA256_PREFIX = "sha256:";
    private final KnowledgePersistenceRepository knowledge;
    private final KnowledgeIndexPersistenceRepository indexes;
    private final EmbeddingCapability embeddings;
    private final EmbeddingInputUnitEstimator estimator;
    private final KnowledgeEmbeddingEntryBatchWriter writer;
    private final Clock clock;

    KnowledgeEmbeddingBatchExecutor(
            KnowledgePersistenceRepository knowledge,
            KnowledgeIndexPersistenceRepository indexes,
            EmbeddingCapability embeddings,
            EmbeddingInputUnitEstimator estimator,
            KnowledgeEmbeddingEntryBatchWriter writer,
            Clock clock) {
        this.knowledge = knowledge;
        this.indexes = indexes;
        this.embeddings = embeddings;
        this.estimator = estimator;
        this.writer = writer;
        this.clock = clock;
    }

    KnowledgeEmbeddingBatchPlan prepare(KnowledgeEmbeddingBatchRequest request) {
        Objects.requireNonNull(request, "APVERO_KNOWLEDGE_EMBEDDING_BATCH_REQUEST_REQUIRED");
        WorkspaceScope scope = request.scope();
        BuildRow build = indexes.findBuild(scope, request.buildId())
                .orElseThrow(() -> new IllegalArgumentException("APVERO_KNOWLEDGE_BUILD_NOT_FOUND"));
        if (build.status() != BuildStatus.EMBEDDING || build.currentStep() != BuildStep.EMBEDDING) {
            throw new IllegalStateException("APVERO_KNOWLEDGE_BUILD_NOT_EMBEDDING");
        }

        EmbeddingRouteSnapshot route =
                embeddings.resolveEmbeddingRoute(scope.workspaceId(), build.embeddingRouteId());
        validateRoute(scope, build, route);
        List<CandidateChunk> canonical = canonicalChunks(scope, build);
        Map<UUID, CandidateChunk> byId = new HashMap<>();
        for (CandidateChunk candidate : canonical) {
            byId.put(candidate.chunk().id(), candidate);
        }

        List<PlannedChunk> selected = new ArrayList<>(request.orderedChunkIds().size());
        int previousOrdinal = -1;
        long estimatedUnits = 0;
        for (UUID chunkId : request.orderedChunkIds()) {
            CandidateChunk candidate = byId.get(chunkId);
            if (candidate == null || candidate.entryOrdinal() <= previousOrdinal) {
                throw new IllegalArgumentException("APVERO_KNOWLEDGE_EMBEDDING_CHUNK_ORDER_INVALID");
            }
            previousOrdinal = candidate.entryOrdinal();
            String recomputedDigest = sha256(candidate.chunk().text().getBytes(StandardCharsets.UTF_8));
            if (!recomputedDigest.equals(candidate.chunk().contentDigest())) {
                throw new IllegalStateException("APVERO_KNOWLEDGE_CHUNK_DIGEST_MISMATCH");
            }
            long chunkUnits = estimator.estimateUnits(candidate.chunk().text());
            if (chunkUnits > route.profile().maximumInputTokens()) {
                throw new IllegalArgumentException("APVERO_KNOWLEDGE_EMBEDDING_CHUNK_OVERSIZED");
            }
            try {
                estimatedUnits = Math.addExact(estimatedUnits, chunkUnits);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "APVERO_KNOWLEDGE_EMBEDDING_INPUT_LIMIT_EXCEEDED", exception);
            }
            selected.add(new PlannedChunk(
                    candidate.revision().sourceId(),
                    candidate.revision().sourceRevisionId(),
                    candidate.chunk().documentId(),
                    candidate.chunk().id(),
                    candidate.entryOrdinal(),
                    candidate.chunk().text(),
                    candidate.chunk().contentDigest()));
        }
        if (selected.size() > route.profile().maximumBatchSize()
                || selected.size() > build.maximumBatchSize()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_EMBEDDING_BATCH_LIMIT_EXCEEDED");
        }
        if (estimatedUnits > route.profile().maximumInputTokens()
                || estimatedUnits > build.maximumInputTokens()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_EMBEDDING_INPUT_LIMIT_EXCEEDED");
        }

        KnowledgeEmbeddingBatchState state =
                entryState(scope, build, request.batchOrdinal(), selected);
        String identity = idempotencyIdentity(build, request.batchOrdinal(), selected);
        EmbeddingExecutionQuote quote =
                embeddings.quote(scope.workspaceId(), build.embeddingRouteId(), estimatedUnits);
        validateRoute(scope, build, quote.route());
        return new KnowledgeEmbeddingBatchPlan(
                scope,
                build,
                request.batchOrdinal(),
                identity,
                estimatedUnits,
                quote,
                state,
                selected);
    }

    KnowledgeEmbeddingEntryBatchWriter.BatchWriteOutcome persist(
            KnowledgeEmbeddingBatchPlan plan,
            EmbeddingExecutionResult result) {
        Objects.requireNonNull(plan, "APVERO_KNOWLEDGE_EMBEDDING_BATCH_PLAN_REQUIRED");
        Objects.requireNonNull(result, "APVERO_EMBEDDING_RESULT_REQUIRED");
        result.validateAgainst(plan.executionRequest());
        if (!result.routeId().equals(plan.build().embeddingRouteId())
                || !result.routeReference().equals(plan.build().embeddingRouteReference())
                || result.dimension() != plan.build().vectorDimension()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_EMBEDDING_RESULT_MISMATCH");
        }

        OffsetDateTime createdAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<EntryRow> rows = new ArrayList<>(plan.orderedChunks().size());
        for (int position = 0; position < plan.orderedChunks().size(); position++) {
            PlannedChunk chunk = plan.orderedChunks().get(position);
            EmbeddingVectorOutput output = result.orderedOutputs().get(position);
            rows.add(new EntryRow(
                    stableId("apvero:knowledge-index-entry:"
                            + plan.build().id() + ':' + chunk.chunkId()),
                    plan.scope().tenantId(),
                    plan.scope().workspaceId(),
                    plan.build().id(),
                    plan.build().knowledgeIndexId(),
                    plan.build().knowledgeBaseId(),
                    chunk.sourceId(),
                    chunk.sourceRevisionId(),
                    chunk.documentId(),
                    chunk.chunkId(),
                    chunk.entryOrdinal(),
                    output.vector(),
                    result.dimension(),
                    vectorDigest(output.vector()),
                    chunk.contentDigest(),
                    plan.batchOrdinal(),
                    plan.build().embeddingRouteId(),
                    plan.build().embeddingRouteReference(),
                    createdAt));
        }
        return writer.persist(plan.scope(), plan.build().id(), rows);
    }

    private List<CandidateChunk> canonicalChunks(WorkspaceScope scope, BuildRow build) {
        List<BuildRevisionRow> revisions = indexes.listBuildRevisions(scope, build.id());
        if (revisions.isEmpty()) {
            throw new IllegalStateException("APVERO_KNOWLEDGE_BUILD_REVISIONS_EMPTY");
        }
        List<CandidateChunk> candidates = new ArrayList<>();
        for (BuildRevisionRow revision : revisions) {
            if (!revision.knowledgeIndexId().equals(build.knowledgeIndexId())
                    || !revision.knowledgeBaseId().equals(build.knowledgeBaseId())) {
                throw new IllegalStateException("APVERO_KNOWLEDGE_BUILD_LINEAGE_MISMATCH");
            }
            Map<UUID, DocumentRow> documents = new HashMap<>();
            for (DocumentRow document : knowledge.listDocuments(scope, revision.sourceRevisionId())) {
                documents.put(document.id(), document);
            }
            for (ChunkRow chunk : knowledge.listChunks(scope, revision.sourceRevisionId())) {
                DocumentRow document = documents.get(chunk.documentId());
                if (document == null) {
                    throw new IllegalStateException("APVERO_KNOWLEDGE_BUILD_LINEAGE_MISMATCH");
                }
                candidates.add(new CandidateChunk(revision, document, chunk, -1));
            }
        }
        candidates.sort(Comparator
                .comparingInt((CandidateChunk item) -> item.revision().sourceSetOrdinal())
                .thenComparingInt(item -> item.document().ordinal())
                .thenComparingInt(item -> item.chunk().ordinal())
                .thenComparing(item -> item.chunk().id()));
        List<CandidateChunk> ordered = new ArrayList<>(candidates.size());
        for (int ordinal = 0; ordinal < candidates.size(); ordinal++) {
            CandidateChunk candidate = candidates.get(ordinal);
            ordered.add(new CandidateChunk(
                    candidate.revision(), candidate.document(), candidate.chunk(), ordinal));
        }
        return ordered;
    }

    private KnowledgeEmbeddingBatchState entryState(
            WorkspaceScope scope,
            BuildRow build,
            int batchOrdinal,
            List<PlannedChunk> selected) {
        Map<UUID, EntryRow> existing = new HashMap<>();
        for (EntryRow row : indexes.listEntries(scope, build.id())) {
            existing.put(row.chunkId(), row);
        }
        int present = (int) selected.stream()
                .filter(chunk -> existing.containsKey(chunk.chunkId()))
                .count();
        if (present == 0) {
            return KnowledgeEmbeddingBatchState.MISSING;
        }
        if (present != selected.size()) {
            throw new IllegalStateException("APVERO_KNOWLEDGE_ENTRY_BATCH_PARTIAL");
        }
        for (PlannedChunk chunk : selected) {
            if (!sameLineage(existing.get(chunk.chunkId()), build, batchOrdinal, chunk)) {
                throw new IllegalStateException("APVERO_KNOWLEDGE_ENTRY_BATCH_CONFLICT");
            }
        }
        return KnowledgeEmbeddingBatchState.COMPLETE_EQUAL;
    }

    private static boolean sameLineage(
            EntryRow row,
            BuildRow build,
            int batchOrdinal,
            PlannedChunk chunk) {
        return row.id().equals(stableId(
                        "apvero:knowledge-index-entry:" + build.id() + ':' + chunk.chunkId()))
                && row.knowledgeIndexBuildId().equals(build.id())
                && row.knowledgeIndexId().equals(build.knowledgeIndexId())
                && row.knowledgeBaseId().equals(build.knowledgeBaseId())
                && row.sourceId().equals(chunk.sourceId())
                && row.sourceRevisionId().equals(chunk.sourceRevisionId())
                && row.documentId().equals(chunk.documentId())
                && row.chunkId().equals(chunk.chunkId())
                && row.entryOrdinal() == chunk.entryOrdinal()
                && row.normalizedInputDigest().equals(chunk.contentDigest())
                && row.batchOrdinal() == batchOrdinal
                && row.vectorDimension() == build.vectorDimension()
                && row.vectorDigest().equals(vectorDigest(row.embedding()))
                && row.embeddingRouteId().equals(build.embeddingRouteId())
                && row.embeddingRouteReference().equals(build.embeddingRouteReference());
    }

    private static void validateRoute(
            WorkspaceScope scope,
            BuildRow build,
            EmbeddingRouteSnapshot route) {
        if (!route.tenantId().equals(scope.tenantId())
                || !route.workspaceId().equals(scope.workspaceId())
                || !route.id().equals(build.embeddingRouteId())
                || !route.reference().equals(build.embeddingRouteReference())
                || route.profile().dimension() != build.vectorDimension()
                || route.profile().maximumInputTokens() != build.maximumInputTokens()
                || route.profile().maximumBatchSize() != build.maximumBatchSize()
                || !route.profile().normalization().name().equals(build.normalization())) {
            throw new IllegalStateException("APVERO_KNOWLEDGE_BUILD_ROUTE_PROFILE_MISMATCH");
        }
    }

    private static String idempotencyIdentity(
            BuildRow build,
            int batchOrdinal,
            List<PlannedChunk> chunks) {
        MessageDigest digest = sha256Digest();
        update(digest, build.id().toString());
        update(digest, Integer.toString(batchOrdinal));
        update(digest, build.embeddingRouteId().toString());
        update(digest, build.embeddingRouteReference());
        for (PlannedChunk chunk : chunks) {
            update(digest, chunk.chunkId().toString());
            update(digest, chunk.contentDigest());
        }
        return "knowledge-embedding:" + HexFormat.of().formatHex(digest.digest());
    }

    private static String vectorDigest(List<Float> vector) {
        ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(vector.size(), Float.BYTES))
                .order(ByteOrder.BIG_ENDIAN);
        for (Float value : vector) {
            bytes.putInt(Float.floatToIntBits(value));
        }
        return sha256(bytes.array());
    }

    private static UUID stableId(String identity) {
        byte[] digest = sha256Digest().digest(identity.getBytes(StandardCharsets.UTF_8));
        ByteBuffer bytes = ByteBuffer.wrap(digest);
        long most = bytes.getLong();
        long least = bytes.getLong();
        most = (most & 0xffffffffffff0fffL) | 0x0000000000005000L;
        least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    private static String sha256(byte[] bytes) {
        return SHA256_PREFIX + HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("APVERO_SHA256_UNAVAILABLE", exception);
        }
    }

    private record CandidateChunk(
            BuildRevisionRow revision,
            DocumentRow document,
            ChunkRow chunk,
            int entryOrdinal) {}
}
