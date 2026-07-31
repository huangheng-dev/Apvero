package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeCanonicalDigests.DigestBuilder;
import io.apvero.platform.knowledge.internal.KnowledgeIndexArtifactManifest.ChunkManifest;
import io.apvero.platform.knowledge.internal.KnowledgeIndexArtifactManifest.DocumentManifest;
import io.apvero.platform.knowledge.internal.KnowledgeIndexArtifactManifest.EntryManifest;
import io.apvero.platform.knowledge.internal.KnowledgeIndexArtifactManifest.SourceManifest;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildSourceCandidateRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ChunkRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.DocumentRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SnapshotStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class KnowledgeIndexArtifactValidator {
    private static final Pattern DIGEST_PATTERN = Pattern.compile("^sha256:[a-f0-9]{64}$");
    private static final String VALIDATION_DOMAIN = "apvero-knowledge-index-validation-v1";
    private static final String ARTIFACT_DOMAIN = "apvero-knowledge-index-artifact-v1";

    KnowledgeIndexArtifactManifest validate(KnowledgeIndexArtifactEvidence evidence) {
        WorkspaceScope scope = evidence.scope();
        BuildRow build = evidence.build();
        requireScope(scope, build.tenantId(), build.workspaceId());
        validateRoute(scope, build, evidence.route());

        List<BuildRevisionRow> orderedRevisions = evidence.buildRevisions().stream()
                .sorted(Comparator.comparingInt(BuildRevisionRow::sourceSetOrdinal)
                        .thenComparing(BuildRevisionRow::id))
                .toList();
        if (orderedRevisions.size() != build.requestedSourceCount()
                || orderedRevisions.isEmpty()) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_SOURCE_MEMBERSHIP_INVALID");
        }

        Map<UUID, SourceRevisionRow> sourceRevisions = uniqueSourceRevisions(evidence.sourceRevisions());
        Map<UUID, List<DocumentRow>> documentsByRevision =
                documentsByRevision(scope, evidence.documents(), sourceRevisions.keySet());
        Map<UUID, List<ChunkRow>> chunksByRevision =
                chunksByRevision(scope, evidence.chunks(), sourceRevisions.keySet());

        List<SourceManifest> sources = new ArrayList<>(orderedRevisions.size());
        List<DocumentManifest> documents = new ArrayList<>();
        List<ChunkManifest> chunks = new ArrayList<>();
        List<BuildSourceCandidateRow> sourceCandidates = new ArrayList<>(orderedRevisions.size());
        Set<UUID> selectedRevisionIds = new HashSet<>();

        for (int sourceOrdinal = 0; sourceOrdinal < orderedRevisions.size(); sourceOrdinal++) {
            BuildRevisionRow revision = orderedRevisions.get(sourceOrdinal);
            validateBuildRevision(scope, build, revision, sourceOrdinal);
            if (!selectedRevisionIds.add(revision.sourceRevisionId())) {
                throw integrity("APVERO_KNOWLEDGE_ARTIFACT_SOURCE_MEMBERSHIP_INVALID");
            }
            SourceRevisionRow sourceRevision = sourceRevisions.get(revision.sourceRevisionId());
            validateSourceRevision(scope, revision, sourceRevision);

            List<DocumentRow> orderedDocuments = orderedDocuments(
                    revision,
                    documentsByRevision.getOrDefault(revision.sourceRevisionId(), List.of()));
            appendDocuments(revision, orderedDocuments, documents);
            List<ChunkRow> revisionChunks =
                    chunksByRevision.getOrDefault(revision.sourceRevisionId(), List.of());
            int sourceChunkCount = appendChunks(
                    scope, revision, orderedDocuments, revisionChunks, chunks);

            sources.add(new SourceManifest(
                    revision.id(),
                    revision.sourceId(),
                    revision.sourceRevisionId(),
                    revision.sourceSetOrdinal(),
                    revision.sourceContentDigest(),
                    revision.parserVersion(),
                    revision.chunkerVersion(),
                    orderedDocuments.size(),
                    sourceChunkCount));
            sourceCandidates.add(new BuildSourceCandidateRow(
                    revision.sourceId(),
                    revision.sourceRevisionId(),
                    revision.sourceContentDigest(),
                    revision.parserVersion(),
                    revision.chunkerVersion(),
                    orderedDocuments.size(),
                    sourceChunkCount));
        }

        if (selectedRevisionIds.size() != sourceRevisions.size()) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_SOURCE_MEMBERSHIP_INVALID");
        }
        if (chunks.size() != build.requestedChunkCount()
                || chunks.isEmpty()) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_CHUNK_MEMBERSHIP_INVALID");
        }
        String sourceSetDigest = KnowledgeIndexBuildDigests.sourceSet(sourceCandidates);
        if (!validDigest(build.sourceSetDigest())
                || !build.sourceSetDigest().equals(sourceSetDigest)) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_SOURCE_SET_DIGEST_MISMATCH");
        }

        List<EntryManifest> entries = validateEntries(scope, build, chunks, evidence.entries());
        String validationDigest =
                validationDigest(build, sources, documents, chunks, entries);
        String artifactDigest =
                artifactDigest(build, sources, chunks, entries);
        return new KnowledgeIndexArtifactManifest(
                build.tenantId(),
                build.workspaceId(),
                build.id(),
                build.knowledgeIndexId(),
                build.knowledgeBaseId(),
                build.requestedVersion(),
                build.sourceSetDigest(),
                build.embeddingRouteId(),
                build.embeddingRouteReference(),
                build.vectorDimension(),
                build.maximumInputTokens(),
                build.maximumBatchSize(),
                build.normalization(),
                sources,
                documents,
                chunks,
                entries,
                validationDigest,
                artifactDigest);
    }

    private static Map<UUID, SourceRevisionRow> uniqueSourceRevisions(
            List<SourceRevisionRow> revisions) {
        Map<UUID, SourceRevisionRow> result = new HashMap<>();
        for (SourceRevisionRow revision : revisions) {
            if (revision == null || result.put(revision.id(), revision) != null) {
                throw integrity("APVERO_KNOWLEDGE_ARTIFACT_SOURCE_MEMBERSHIP_INVALID");
            }
        }
        return result;
    }

    private static Map<UUID, List<DocumentRow>> documentsByRevision(
            WorkspaceScope scope,
            List<DocumentRow> documents,
            Set<UUID> selectedRevisionIds) {
        Map<UUID, List<DocumentRow>> result = new HashMap<>();
        Set<UUID> ids = new HashSet<>();
        for (DocumentRow document : documents) {
            if (document == null
                    || !ids.add(document.id())
                    || !selectedRevisionIds.contains(document.sourceRevisionId())) {
                throw integrity("APVERO_KNOWLEDGE_ARTIFACT_DOCUMENT_MEMBERSHIP_INVALID");
            }
            requireScope(scope, document.tenantId(), document.workspaceId());
            result.computeIfAbsent(document.sourceRevisionId(), ignored -> new ArrayList<>())
                    .add(document);
        }
        return result;
    }

    private static Map<UUID, List<ChunkRow>> chunksByRevision(
            WorkspaceScope scope,
            List<ChunkRow> chunks,
            Set<UUID> selectedRevisionIds) {
        Map<UUID, List<ChunkRow>> result = new HashMap<>();
        Set<UUID> ids = new HashSet<>();
        for (ChunkRow chunk : chunks) {
            if (chunk == null
                    || !ids.add(chunk.id())
                    || !selectedRevisionIds.contains(chunk.sourceRevisionId())) {
                throw integrity("APVERO_KNOWLEDGE_ARTIFACT_CHUNK_MEMBERSHIP_INVALID");
            }
            requireScope(scope, chunk.tenantId(), chunk.workspaceId());
            result.computeIfAbsent(chunk.sourceRevisionId(), ignored -> new ArrayList<>())
                    .add(chunk);
        }
        return result;
    }

    private static void validateBuildRevision(
            WorkspaceScope scope,
            BuildRow build,
            BuildRevisionRow revision,
            int expectedOrdinal) {
        if (revision == null) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_SOURCE_MEMBERSHIP_INVALID");
        }
        requireScope(scope, revision.tenantId(), revision.workspaceId());
        if (!Objects.equals(revision.knowledgeIndexBuildId(), build.id())
                || !Objects.equals(revision.knowledgeIndexId(), build.knowledgeIndexId())
                || !Objects.equals(revision.knowledgeBaseId(), build.knowledgeBaseId())
                || revision.sourceSetOrdinal() != expectedOrdinal
                || !validDigest(revision.sourceContentDigest())
                || blank(revision.parserVersion())
                || blank(revision.chunkerVersion())) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_SOURCE_MEMBERSHIP_INVALID");
        }
    }

    private static void validateSourceRevision(
            WorkspaceScope scope,
            BuildRevisionRow buildRevision,
            SourceRevisionRow sourceRevision) {
        if (sourceRevision == null) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_SOURCE_MEMBERSHIP_INVALID");
        }
        requireScope(scope, sourceRevision.tenantId(), sourceRevision.workspaceId());
        if (!Objects.equals(sourceRevision.sourceId(), buildRevision.sourceId())
                || !Objects.equals(sourceRevision.id(), buildRevision.sourceRevisionId())
                || !Objects.equals(sourceRevision.contentDigest(), buildRevision.sourceContentDigest())
                || (sourceRevision.parserVersion() != null
                        && !Objects.equals(sourceRevision.parserVersion(), buildRevision.parserVersion()))
                || (sourceRevision.chunkerVersion() != null
                        && !Objects.equals(sourceRevision.chunkerVersion(), buildRevision.chunkerVersion()))
                || sourceRevision.snapshotStatus() != SnapshotStatus.SNAPSHOTTED) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_SOURCE_MEMBERSHIP_INVALID");
        }
    }

    private static List<DocumentRow> orderedDocuments(
            BuildRevisionRow revision,
            List<DocumentRow> documents) {
        List<DocumentRow> ordered = documents.stream()
                .sorted(Comparator.comparingInt(DocumentRow::ordinal)
                        .thenComparing(DocumentRow::id))
                .toList();
        for (int ordinal = 0; ordinal < ordered.size(); ordinal++) {
            DocumentRow document = ordered.get(ordinal);
            if (!Objects.equals(document.sourceRevisionId(), revision.sourceRevisionId())
                    || document.ordinal() != ordinal
                    || !Objects.equals(document.parserVersion(), revision.parserVersion())
                    || !validDigest(document.normalizedTextDigest())
                    || blank(document.processingProfile())) {
                throw integrity("APVERO_KNOWLEDGE_ARTIFACT_DOCUMENT_MEMBERSHIP_INVALID");
            }
        }
        return ordered;
    }

    private static void appendDocuments(
            BuildRevisionRow revision,
            List<DocumentRow> documents,
            List<DocumentManifest> target) {
        for (DocumentRow document : documents) {
            target.add(new DocumentManifest(
                    revision.sourceId(),
                    revision.sourceRevisionId(),
                    document.id(),
                    document.ordinal(),
                    document.normalizedTextDigest(),
                    document.parserVersion(),
                    document.processingProfile()));
        }
    }

    private static int appendChunks(
            WorkspaceScope scope,
            BuildRevisionRow revision,
            List<DocumentRow> documents,
            List<ChunkRow> revisionChunks,
            List<ChunkManifest> target) {
        Map<UUID, List<ChunkRow>> byDocument = new HashMap<>();
        for (ChunkRow chunk : revisionChunks) {
            byDocument.computeIfAbsent(chunk.documentId(), ignored -> new ArrayList<>())
                    .add(chunk);
        }
        int initialSize = target.size();
        for (DocumentRow document : documents) {
            List<ChunkRow> ordered = byDocument.remove(document.id());
            if (ordered == null) {
                ordered = List.of();
            } else {
                ordered = ordered.stream()
                        .sorted(Comparator.comparingInt(ChunkRow::ordinal)
                                .thenComparing(ChunkRow::id))
                        .toList();
            }
            for (int chunkOrdinal = 0; chunkOrdinal < ordered.size(); chunkOrdinal++) {
                ChunkRow chunk = ordered.get(chunkOrdinal);
                requireScope(scope, chunk.tenantId(), chunk.workspaceId());
                if (!Objects.equals(chunk.sourceRevisionId(), revision.sourceRevisionId())
                        || !Objects.equals(chunk.documentId(), document.id())
                        || chunk.ordinal() != chunkOrdinal
                        || !Objects.equals(chunk.chunkerVersion(), revision.chunkerVersion())
                        || chunk.text() == null
                        || chunk.text().isEmpty()
                        || !validDigest(chunk.contentDigest())
                        || !KnowledgeCanonicalDigests.text(chunk.text()).equals(chunk.contentDigest())) {
                    throw integrity("APVERO_KNOWLEDGE_ARTIFACT_CHUNK_MEMBERSHIP_INVALID");
                }
                target.add(new ChunkManifest(
                        revision.sourceId(),
                        revision.sourceRevisionId(),
                        document.id(),
                        document.ordinal(),
                        chunk.id(),
                        chunk.ordinal(),
                        target.size(),
                        chunk.contentDigest()));
            }
        }
        if (!byDocument.isEmpty()) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_CHUNK_MEMBERSHIP_INVALID");
        }
        return target.size() - initialSize;
    }

    private static List<EntryManifest> validateEntries(
            WorkspaceScope scope,
            BuildRow build,
            List<ChunkManifest> chunks,
            List<EntryRow> suppliedEntries) {
        if (suppliedEntries.size() != chunks.size()) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_ENTRY_MEMBERSHIP_INVALID");
        }
        Map<Integer, EntryRow> byOrdinal = new HashMap<>();
        Set<UUID> entryIds = new HashSet<>();
        Set<UUID> chunkIds = new HashSet<>();
        for (EntryRow entry : suppliedEntries) {
            if (entry == null
                    || byOrdinal.put(entry.entryOrdinal(), entry) != null
                    || !entryIds.add(entry.id())
                    || !chunkIds.add(entry.chunkId())) {
                throw integrity("APVERO_KNOWLEDGE_ARTIFACT_ENTRY_MEMBERSHIP_INVALID");
            }
        }

        List<EntryManifest> result = new ArrayList<>(chunks.size());
        int activeBatchOrdinal = -1;
        for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
            ChunkManifest chunk = chunks.get(ordinal);
            EntryRow entry = byOrdinal.get(ordinal);
            if (entry == null) {
                throw integrity("APVERO_KNOWLEDGE_ARTIFACT_ENTRY_MEMBERSHIP_INVALID");
            }
            requireScope(scope, entry.tenantId(), entry.workspaceId());
            validateEntryLineage(build, chunk, entry, ordinal);
            activeBatchOrdinal = validateBatchOrdinal(entry, ordinal, activeBatchOrdinal);
            String vectorDigest;
            try {
                if (entry.embedding().size() != build.vectorDimension()) {
                    throw integrity("APVERO_KNOWLEDGE_ARTIFACT_VECTOR_INTEGRITY_INVALID");
                }
                vectorDigest = KnowledgeCanonicalDigests.vector(entry.embedding());
            } catch (IllegalArgumentException exception) {
                throw integrity("APVERO_KNOWLEDGE_ARTIFACT_VECTOR_INTEGRITY_INVALID");
            }
            if (!validDigest(entry.vectorDigest())
                    || !entry.vectorDigest().equals(vectorDigest)) {
                throw integrity("APVERO_KNOWLEDGE_ARTIFACT_VECTOR_DIGEST_MISMATCH");
            }
            result.add(new EntryManifest(
                    entry.id(),
                    entry.sourceId(),
                    entry.sourceRevisionId(),
                    entry.documentId(),
                    entry.chunkId(),
                    entry.entryOrdinal(),
                    entry.batchOrdinal(),
                    entry.normalizedInputDigest(),
                    vectorDigest));
        }
        return List.copyOf(result);
    }

    private static void validateEntryLineage(
            BuildRow build,
            ChunkManifest chunk,
            EntryRow entry,
            int expectedOrdinal) {
        UUID expectedEntryId = KnowledgeCanonicalDigests.stableId(
                "apvero:knowledge-index-entry:" + build.id() + ':' + chunk.chunkId());
        if (!Objects.equals(entry.id(), expectedEntryId)
                || !Objects.equals(entry.knowledgeIndexBuildId(), build.id())
                || !Objects.equals(entry.knowledgeIndexId(), build.knowledgeIndexId())
                || !Objects.equals(entry.knowledgeBaseId(), build.knowledgeBaseId())
                || !Objects.equals(entry.sourceId(), chunk.sourceId())
                || !Objects.equals(entry.sourceRevisionId(), chunk.sourceRevisionId())
                || !Objects.equals(entry.documentId(), chunk.documentId())
                || !Objects.equals(entry.chunkId(), chunk.chunkId())
                || entry.entryOrdinal() != expectedOrdinal
                || !Objects.equals(entry.normalizedInputDigest(), chunk.contentDigest())
                || entry.vectorDimension() != build.vectorDimension()
                || !Objects.equals(entry.embeddingRouteId(), build.embeddingRouteId())
                || !Objects.equals(entry.embeddingRouteReference(), build.embeddingRouteReference())) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_ENTRY_LINEAGE_INVALID");
        }
    }

    private static int validateBatchOrdinal(
            EntryRow entry,
            int entryOrdinal,
            int activeBatchOrdinal) {
        if (entry.batchOrdinal() < 0 || entry.batchOrdinal() > entryOrdinal) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_ENTRY_ORDINAL_INVALID");
        }
        if (entryOrdinal == 0 && entry.batchOrdinal() != 0) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_ENTRY_ORDINAL_INVALID");
        }
        if (entry.batchOrdinal() != activeBatchOrdinal) {
            if (entry.batchOrdinal() != entryOrdinal
                    || entry.batchOrdinal() <= activeBatchOrdinal) {
                throw integrity("APVERO_KNOWLEDGE_ARTIFACT_ENTRY_ORDINAL_INVALID");
            }
            return entry.batchOrdinal();
        }
        return activeBatchOrdinal;
    }

    private static void validateRoute(
            WorkspaceScope scope,
            BuildRow build,
            EmbeddingRouteSnapshot route) {
        if (!Objects.equals(route.tenantId(), scope.tenantId())
                || !Objects.equals(route.workspaceId(), scope.workspaceId())
                || !Objects.equals(route.id(), build.embeddingRouteId())
                || !Objects.equals(route.reference(), build.embeddingRouteReference())
                || route.profile().dimension() != build.vectorDimension()
                || route.profile().maximumInputTokens() != build.maximumInputTokens()
                || route.profile().maximumBatchSize() != build.maximumBatchSize()
                || !route.profile().normalization().name().equals(build.normalization())) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_ROUTE_PROFILE_MISMATCH");
        }
    }

    private static String validationDigest(
            BuildRow build,
            List<SourceManifest> sources,
            List<DocumentManifest> documents,
            List<ChunkManifest> chunks,
            List<EntryManifest> entries) {
        DigestBuilder digest = KnowledgeCanonicalDigests.builder(VALIDATION_DOMAIN);
        digest.addUuid(build.tenantId());
        digest.addUuid(build.workspaceId());
        digest.addUuid(build.id());
        digest.addUuid(build.knowledgeIndexId());
        digest.addUuid(build.knowledgeBaseId());
        digest.addString(build.requestedVersion());
        digest.addString(build.sourceSetDigest());
        addRoute(digest, build);
        addSources(digest, sources, true);
        addDocuments(digest, documents);
        addChunks(digest, chunks);
        addEntries(digest, entries, true);
        return digest.finish();
    }

    private static String artifactDigest(
            BuildRow build,
            List<SourceManifest> sources,
            List<ChunkManifest> chunks,
            List<EntryManifest> entries) {
        DigestBuilder digest = KnowledgeCanonicalDigests.builder(ARTIFACT_DOMAIN);
        addRoute(digest, build);
        addSources(digest, sources, false);
        addChunks(digest, chunks);
        addEntries(digest, entries, false);
        return digest.finish();
    }

    private static void addRoute(DigestBuilder digest, BuildRow build) {
        digest.addUuid(build.embeddingRouteId());
        digest.addString(build.embeddingRouteReference());
        digest.addInt(build.vectorDimension());
        digest.addInt(build.maximumInputTokens());
        digest.addInt(build.maximumBatchSize());
        digest.addString(build.normalization());
    }

    private static void addSources(
            DigestBuilder digest,
            List<SourceManifest> sources,
            boolean includeBuildRevisionId) {
        digest.addInt(sources.size());
        for (SourceManifest source : sources) {
            if (includeBuildRevisionId) {
                digest.addUuid(source.buildRevisionId());
            }
            digest.addUuid(source.sourceId());
            digest.addUuid(source.sourceRevisionId());
            digest.addInt(source.sourceSetOrdinal());
            digest.addString(source.sourceContentDigest());
            digest.addString(source.parserVersion());
            digest.addString(source.chunkerVersion());
            digest.addInt(source.documentCount());
            digest.addInt(source.chunkCount());
        }
    }

    private static void addChunks(DigestBuilder digest, List<ChunkManifest> chunks) {
        digest.addInt(chunks.size());
        for (ChunkManifest chunk : chunks) {
            digest.addUuid(chunk.sourceId());
            digest.addUuid(chunk.sourceRevisionId());
            digest.addUuid(chunk.documentId());
            digest.addInt(chunk.documentOrdinal());
            digest.addUuid(chunk.chunkId());
            digest.addInt(chunk.chunkOrdinal());
            digest.addInt(chunk.entryOrdinal());
            digest.addString(chunk.contentDigest());
        }
    }

    private static void addDocuments(
            DigestBuilder digest,
            List<DocumentManifest> documents) {
        digest.addInt(documents.size());
        for (DocumentManifest document : documents) {
            digest.addUuid(document.sourceId());
            digest.addUuid(document.sourceRevisionId());
            digest.addUuid(document.documentId());
            digest.addInt(document.documentOrdinal());
            digest.addString(document.normalizedTextDigest());
            digest.addString(document.parserVersion());
            digest.addString(document.processingProfile());
        }
    }

    private static void addEntries(
            DigestBuilder digest,
            List<EntryManifest> entries,
            boolean includeLineageAndInput) {
        digest.addInt(entries.size());
        for (EntryManifest entry : entries) {
            digest.addUuid(entry.entryId());
            if (includeLineageAndInput) {
                digest.addUuid(entry.sourceId());
                digest.addUuid(entry.sourceRevisionId());
                digest.addUuid(entry.documentId());
                digest.addUuid(entry.chunkId());
                digest.addInt(entry.entryOrdinal());
                digest.addInt(entry.batchOrdinal());
                digest.addString(entry.normalizedInputDigest());
            }
            digest.addString(entry.vectorDigest());
        }
    }

    private static void requireScope(
            WorkspaceScope scope,
            UUID tenantId,
            UUID workspaceId) {
        if (!scope.tenantId().equals(tenantId)
                || !scope.workspaceId().equals(workspaceId)) {
            throw integrity("APVERO_KNOWLEDGE_ARTIFACT_SCOPE_MISMATCH");
        }
    }

    private static boolean validDigest(String value) {
        return value != null && DIGEST_PATTERN.matcher(value).matches();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException integrity(String code) {
        return new IllegalStateException(code);
    }
}
