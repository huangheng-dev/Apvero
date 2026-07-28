package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.capability.EmbeddingNormalization;
import io.apvero.platform.capability.EmbeddingRouteProfile;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.capability.ModelRouteCapability;
import io.apvero.platform.capability.ModelRouteStatus;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildSourceCandidateRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ChunkRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.DocumentRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SnapshotStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeIndexArtifactValidatorTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-28T00:00:00Z");
    private static final UUID TENANT_ID = id(1);
    private static final UUID WORKSPACE_ID = id(2);
    private static final UUID BASE_ID = id(3);
    private static final UUID INDEX_ID = id(4);
    private static final UUID ROUTE_ID = id(5);
    private static final UUID MODEL_ID = id(6);
    private static final UUID BUILD_ID = id(7);
    private static final SourceFixture FIRST = source(0, 10, 'a', "first", List.of(1f, 0f, 0f));
    private static final SourceFixture SECOND = source(1, 20, 'b', "second", List.of(-0.0f, 1f, 0f));
    private final KnowledgeIndexArtifactValidator validator = new KnowledgeIndexArtifactValidator();

    @Test
    void reconstructsTheSameCanonicalManifestFromShuffledEvidence() {
        KnowledgeIndexArtifactManifest first = validator.validate(evidence(true));
        KnowledgeIndexArtifactManifest repeated = validator.validate(evidence(false));

        assertThat(first).isEqualTo(repeated);
        assertThat(first.sourceCount()).isEqualTo(2);
        assertThat(first.documentCount()).isEqualTo(2);
        assertThat(first.chunkCount()).isEqualTo(2);
        assertThat(first.entryCount()).isEqualTo(2);
        assertThat(first.sources())
                .extracting(KnowledgeIndexArtifactManifest.SourceManifest::sourceSetOrdinal)
                .containsExactly(0, 1);
        assertThat(first.chunks())
                .extracting(KnowledgeIndexArtifactManifest.ChunkManifest::chunkId)
                .containsExactly(FIRST.chunkId(), SECOND.chunkId());
        assertThat(first.documents())
                .extracting(KnowledgeIndexArtifactManifest.DocumentManifest::documentId)
                .containsExactly(FIRST.documentId(), SECOND.documentId());
        assertThat(first.entries())
                .extracting(KnowledgeIndexArtifactManifest.EntryManifest::entryOrdinal)
                .containsExactly(0, 1);
        assertThat(first.validationDigest()).matches("^sha256:[a-f0-9]{64}$");
        assertThat(first.artifactDigest()).matches("^sha256:[a-f0-9]{64}$");
    }

    @Test
    void validationIdentityChangesWithoutChangingTheArtifactDigest() {
        KnowledgeIndexArtifactEvidence baseline = evidence(false);
        KnowledgeIndexArtifactManifest first = validator.validate(baseline);
        KnowledgeIndexArtifactManifest renamed = validator.validate(withBuild(
                baseline, copyBuild(baseline.build(), "1.0.1", baseline.build().sourceSetDigest())));

        assertThat(renamed.validationDigest()).isNotEqualTo(first.validationDigest());
        assertThat(renamed.artifactDigest()).isEqualTo(first.artifactDigest());
    }

    @Test
    void manifestDigestsIgnoreDefaultLocaleAndTimeZone() {
        Locale originalLocale = Locale.getDefault();
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.US);
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            KnowledgeIndexArtifactManifest first = validator.validate(evidence(false));

            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            KnowledgeIndexArtifactManifest repeated = validator.validate(evidence(true));

            assertThat(repeated.validationDigest()).isEqualTo(first.validationDigest());
            assertThat(repeated.artifactDigest()).isEqualTo(first.artifactDigest());
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    void rejectsScopeRouteAndSourceSetCorruption() {
        KnowledgeIndexArtifactEvidence baseline = evidence(false);

        assertFailure(
                new KnowledgeIndexArtifactEvidence(
                        new WorkspaceScope(id(99), WORKSPACE_ID),
                        baseline.build(),
                        baseline.route(),
                        baseline.buildRevisions(),
                        baseline.sourceRevisions(),
                        baseline.documents(),
                        baseline.chunks(),
                        baseline.entries()),
                "APVERO_KNOWLEDGE_ARTIFACT_SCOPE_MISMATCH");
        assertFailure(
                withRoute(baseline, route(4)),
                "APVERO_KNOWLEDGE_ARTIFACT_ROUTE_PROFILE_MISMATCH");
        assertFailure(
                withBuild(baseline, copyBuild(baseline.build(), "1.0.0", hex('f'))),
                "APVERO_KNOWLEDGE_ARTIFACT_SOURCE_SET_DIGEST_MISMATCH");
    }

    @Test
    void rejectsMembershipOrdinalAndChunkDigestCorruption() {
        KnowledgeIndexArtifactEvidence baseline = evidence(false);
        BuildRevisionRow second = baseline.buildRevisions().get(1);
        BuildRevisionRow duplicateOrdinal = new BuildRevisionRow(
                second.id(),
                second.tenantId(),
                second.workspaceId(),
                second.knowledgeIndexBuildId(),
                second.knowledgeIndexId(),
                second.knowledgeBaseId(),
                second.sourceId(),
                second.sourceRevisionId(),
                second.sourceContentDigest(),
                second.parserVersion(),
                second.chunkerVersion(),
                0,
                second.createdAt());
        assertFailure(
                withBuildRevisions(baseline, List.of(baseline.buildRevisions().getFirst(), duplicateOrdinal)),
                "APVERO_KNOWLEDGE_ARTIFACT_SOURCE_MEMBERSHIP_INVALID");

        ChunkRow firstChunk = baseline.chunks().getFirst();
        assertFailure(
                withChunks(baseline, List.of(copyChunk(firstChunk, hex('e')), baseline.chunks().get(1))),
                "APVERO_KNOWLEDGE_ARTIFACT_CHUNK_MEMBERSHIP_INVALID");
        assertFailure(
                withEntries(baseline, List.of(baseline.entries().getFirst())),
                "APVERO_KNOWLEDGE_ARTIFACT_ENTRY_MEMBERSHIP_INVALID");
    }

    @Test
    void rejectsEntryLineageDigestVectorAndBatchCorruption() {
        KnowledgeIndexArtifactEvidence baseline = evidence(false);
        EntryRow first = baseline.entries().getFirst();
        EntryRow second = baseline.entries().get(1);

        assertFailure(
                withEntries(baseline, List.of(copyEntryIdentity(first, id(99)), second)),
                "APVERO_KNOWLEDGE_ARTIFACT_ENTRY_LINEAGE_INVALID");
        assertFailure(
                withEntries(baseline, List.of(copyEntryInputDigest(first, hex('e')), second)),
                "APVERO_KNOWLEDGE_ARTIFACT_ENTRY_LINEAGE_INVALID");
        assertFailure(
                withEntries(baseline, List.of(copyEntryDigest(first, hex('d')), second)),
                "APVERO_KNOWLEDGE_ARTIFACT_VECTOR_DIGEST_MISMATCH");
        assertFailure(
                withEntries(baseline, List.of(copyEntryVector(first, List.of(0f, -0.0f)), second)),
                "APVERO_KNOWLEDGE_ARTIFACT_VECTOR_INTEGRITY_INVALID");
        assertFailure(
                withEntries(baseline, List.of(first, copyEntryBatch(second, 2))),
                "APVERO_KNOWLEDGE_ARTIFACT_ENTRY_ORDINAL_INVALID");
    }

    private static KnowledgeIndexArtifactEvidence evidence(boolean shuffled) {
        BuildRevisionRow firstRevision = buildRevision(FIRST);
        BuildRevisionRow secondRevision = buildRevision(SECOND);
        SourceRevisionRow firstSourceRevision = sourceRevision(FIRST);
        SourceRevisionRow secondSourceRevision = sourceRevision(SECOND);
        DocumentRow firstDocument = document(FIRST);
        DocumentRow secondDocument = document(SECOND);
        ChunkRow firstChunk = chunk(FIRST);
        ChunkRow secondChunk = chunk(SECOND);
        EntryRow firstEntry = entry(FIRST);
        EntryRow secondEntry = entry(SECOND);
        return new KnowledgeIndexArtifactEvidence(
                new WorkspaceScope(TENANT_ID, WORKSPACE_ID),
                build("1.0.0", sourceSetDigest()),
                route(3),
                shuffled ? List.of(secondRevision, firstRevision) : List.of(firstRevision, secondRevision),
                shuffled
                        ? List.of(secondSourceRevision, firstSourceRevision)
                        : List.of(firstSourceRevision, secondSourceRevision),
                shuffled ? List.of(secondDocument, firstDocument) : List.of(firstDocument, secondDocument),
                shuffled ? List.of(secondChunk, firstChunk) : List.of(firstChunk, secondChunk),
                shuffled ? List.of(secondEntry, firstEntry) : List.of(firstEntry, secondEntry));
    }

    private static BuildRow build(String version, String sourceSetDigest) {
        return new BuildRow(
                BUILD_ID,
                TENANT_ID,
                WORKSPACE_ID,
                INDEX_ID,
                BASE_ID,
                version,
                ROUTE_ID,
                "embedding-route@3",
                3,
                8_192,
                64,
                "L2",
                hex('c'),
                sourceSetDigest,
                2,
                2,
                BuildStatus.INDEXING,
                BuildStep.INDEXING,
                1,
                3,
                false,
                null,
                "runner",
                NOW.plusMinutes(5),
                2,
                false,
                2,
                0,
                1,
                null,
                null,
                null,
                null,
                null,
                false,
                "{}",
                NOW,
                null,
                NOW,
                NOW);
    }

    private static BuildRevisionRow buildRevision(SourceFixture fixture) {
        return new BuildRevisionRow(
                fixture.buildRevisionId(),
                TENANT_ID,
                WORKSPACE_ID,
                BUILD_ID,
                INDEX_ID,
                BASE_ID,
                fixture.sourceId(),
                fixture.sourceRevisionId(),
                fixture.sourceDigest(),
                "parser@1",
                "chunker@1",
                fixture.sourceOrdinal(),
                NOW);
    }

    private static SourceRevisionRow sourceRevision(SourceFixture fixture) {
        return new SourceRevisionRow(
                fixture.sourceRevisionId(),
                TENANT_ID,
                WORKSPACE_ID,
                fixture.sourceId(),
                1,
                fixture.sourceDigest(),
                "text/markdown",
                fixture.text().length(),
                "source.md",
                "{}",
                fixture.text().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                SnapshotStatus.SNAPSHOTTED,
                "parser@1",
                "chunker@1",
                NOW);
    }

    private static DocumentRow document(SourceFixture fixture) {
        return new DocumentRow(
                fixture.documentId(),
                TENANT_ID,
                WORKSPACE_ID,
                fixture.sourceRevisionId(),
                0,
                "Document " + fixture.sourceOrdinal(),
                hex('d'),
                "parser@1",
                "processing@1",
                NOW);
    }

    private static ChunkRow chunk(SourceFixture fixture) {
        return new ChunkRow(
                fixture.chunkId(),
                TENANT_ID,
                WORKSPACE_ID,
                fixture.sourceRevisionId(),
                fixture.documentId(),
                0,
                fixture.text(),
                KnowledgeCanonicalDigests.text(fixture.text()),
                0,
                fixture.text().length(),
                null,
                null,
                null,
                null,
                null,
                "chunker@1",
                NOW);
    }

    private static EntryRow entry(SourceFixture fixture) {
        return new EntryRow(
                KnowledgeCanonicalDigests.stableId(
                        "apvero:knowledge-index-entry:" + BUILD_ID + ':' + fixture.chunkId()),
                TENANT_ID,
                WORKSPACE_ID,
                BUILD_ID,
                INDEX_ID,
                BASE_ID,
                fixture.sourceId(),
                fixture.sourceRevisionId(),
                fixture.documentId(),
                fixture.chunkId(),
                fixture.sourceOrdinal(),
                fixture.vector(),
                3,
                KnowledgeCanonicalDigests.vector(fixture.vector()),
                KnowledgeCanonicalDigests.text(fixture.text()),
                0,
                ROUTE_ID,
                "embedding-route@3",
                NOW);
    }

    private static EmbeddingRouteSnapshot route(int dimension) {
        return new EmbeddingRouteSnapshot(
                ROUTE_ID,
                TENANT_ID,
                WORKSPACE_ID,
                "embedding-route",
                3,
                MODEL_ID,
                ModelRouteCapability.EMBEDDING,
                ModelRouteStatus.DEPRECATED,
                30_000,
                new EmbeddingRouteProfile(dimension, 8_192, 64, EmbeddingNormalization.L2),
                false,
                "PROVIDER_DISABLED",
                NOW);
    }

    private static String sourceSetDigest() {
        return KnowledgeIndexBuildDigests.sourceSet(List.of(
                candidate(FIRST),
                candidate(SECOND)));
    }

    private static BuildSourceCandidateRow candidate(SourceFixture fixture) {
        return new BuildSourceCandidateRow(
                fixture.sourceId(),
                fixture.sourceRevisionId(),
                fixture.sourceDigest(),
                "parser@1",
                "chunker@1",
                1,
                1);
    }

    private static SourceFixture source(
            int ordinal,
            long seed,
            char digest,
            String text,
            List<Float> vector) {
        return new SourceFixture(
                ordinal,
                id(seed),
                id(seed + 1),
                id(seed + 2),
                id(seed + 3),
                id(seed + 4),
                hex(digest),
                text,
                vector);
    }

    private static KnowledgeIndexArtifactEvidence withBuild(
            KnowledgeIndexArtifactEvidence evidence,
            BuildRow build) {
        return new KnowledgeIndexArtifactEvidence(
                evidence.scope(),
                build,
                evidence.route(),
                evidence.buildRevisions(),
                evidence.sourceRevisions(),
                evidence.documents(),
                evidence.chunks(),
                evidence.entries());
    }

    private static KnowledgeIndexArtifactEvidence withRoute(
            KnowledgeIndexArtifactEvidence evidence,
            EmbeddingRouteSnapshot route) {
        return new KnowledgeIndexArtifactEvidence(
                evidence.scope(),
                evidence.build(),
                route,
                evidence.buildRevisions(),
                evidence.sourceRevisions(),
                evidence.documents(),
                evidence.chunks(),
                evidence.entries());
    }

    private static KnowledgeIndexArtifactEvidence withBuildRevisions(
            KnowledgeIndexArtifactEvidence evidence,
            List<BuildRevisionRow> revisions) {
        return new KnowledgeIndexArtifactEvidence(
                evidence.scope(),
                evidence.build(),
                evidence.route(),
                revisions,
                evidence.sourceRevisions(),
                evidence.documents(),
                evidence.chunks(),
                evidence.entries());
    }

    private static KnowledgeIndexArtifactEvidence withChunks(
            KnowledgeIndexArtifactEvidence evidence,
            List<ChunkRow> chunks) {
        return new KnowledgeIndexArtifactEvidence(
                evidence.scope(),
                evidence.build(),
                evidence.route(),
                evidence.buildRevisions(),
                evidence.sourceRevisions(),
                evidence.documents(),
                chunks,
                evidence.entries());
    }

    private static KnowledgeIndexArtifactEvidence withEntries(
            KnowledgeIndexArtifactEvidence evidence,
            List<EntryRow> entries) {
        return new KnowledgeIndexArtifactEvidence(
                evidence.scope(),
                evidence.build(),
                evidence.route(),
                evidence.buildRevisions(),
                evidence.sourceRevisions(),
                evidence.documents(),
                evidence.chunks(),
                entries);
    }

    private static BuildRow copyBuild(BuildRow row, String version, String sourceSetDigest) {
        return new BuildRow(
                row.id(),
                row.tenantId(),
                row.workspaceId(),
                row.knowledgeIndexId(),
                row.knowledgeBaseId(),
                version,
                row.embeddingRouteId(),
                row.embeddingRouteReference(),
                row.vectorDimension(),
                row.maximumInputTokens(),
                row.maximumBatchSize(),
                row.normalization(),
                row.requestDigest(),
                sourceSetDigest,
                row.requestedSourceCount(),
                row.requestedChunkCount(),
                row.status(),
                row.currentStep(),
                row.attemptCount(),
                row.maximumAttempts(),
                row.retryable(),
                row.nextAttemptAt(),
                row.leaseOwner(),
                row.leaseUntil(),
                row.lockVersion(),
                row.cancellationRequested(),
                row.embeddedEntryCount(),
                row.validatedEntryCount(),
                row.lastDurableChunkOrdinal(),
                row.validationDigest(),
                row.artifactDigest(),
                row.publishedVersionId(),
                row.errorCode(),
                row.errorCategory(),
                row.reconciliationRequired(),
                row.failureMetadataJson(),
                row.startedAt(),
                row.completedAt(),
                row.createdAt(),
                row.updatedAt());
    }

    private static ChunkRow copyChunk(ChunkRow row, String contentDigest) {
        return new ChunkRow(
                row.id(),
                row.tenantId(),
                row.workspaceId(),
                row.sourceRevisionId(),
                row.documentId(),
                row.ordinal(),
                row.text(),
                contentDigest,
                row.startOffset(),
                row.endOffset(),
                row.pageNumber(),
                row.heading(),
                row.paragraphNumber(),
                row.lineStart(),
                row.lineEnd(),
                row.chunkerVersion(),
                row.createdAt());
    }

    private static EntryRow copyEntryIdentity(EntryRow row, UUID id) {
        return copyEntry(
                row,
                id,
                row.embedding(),
                row.vectorDigest(),
                row.normalizedInputDigest(),
                row.batchOrdinal());
    }

    private static EntryRow copyEntryDigest(EntryRow row, String vectorDigest) {
        return copyEntry(
                row,
                row.id(),
                row.embedding(),
                vectorDigest,
                row.normalizedInputDigest(),
                row.batchOrdinal());
    }

    private static EntryRow copyEntryInputDigest(EntryRow row, String inputDigest) {
        return copyEntry(
                row,
                row.id(),
                row.embedding(),
                row.vectorDigest(),
                inputDigest,
                row.batchOrdinal());
    }

    private static EntryRow copyEntryVector(EntryRow row, List<Float> vector) {
        return copyEntry(
                row,
                row.id(),
                vector,
                row.vectorDigest(),
                row.normalizedInputDigest(),
                row.batchOrdinal());
    }

    private static EntryRow copyEntryBatch(EntryRow row, int batchOrdinal) {
        return copyEntry(
                row,
                row.id(),
                row.embedding(),
                row.vectorDigest(),
                row.normalizedInputDigest(),
                batchOrdinal);
    }

    private static EntryRow copyEntry(
            EntryRow row,
            UUID id,
            List<Float> vector,
            String vectorDigest,
            String inputDigest,
            int batchOrdinal) {
        return new EntryRow(
                id,
                row.tenantId(),
                row.workspaceId(),
                row.knowledgeIndexBuildId(),
                row.knowledgeIndexId(),
                row.knowledgeBaseId(),
                row.sourceId(),
                row.sourceRevisionId(),
                row.documentId(),
                row.chunkId(),
                row.entryOrdinal(),
                vector,
                row.vectorDimension(),
                vectorDigest,
                inputDigest,
                batchOrdinal,
                row.embeddingRouteId(),
                row.embeddingRouteReference(),
                row.createdAt());
    }

    private static void assertFailure(KnowledgeIndexArtifactEvidence evidence, String code) {
        assertThatThrownBy(() -> new KnowledgeIndexArtifactValidator().validate(evidence))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(code);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }

    private static String hex(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record SourceFixture(
            int sourceOrdinal,
            UUID sourceId,
            UUID sourceRevisionId,
            UUID buildRevisionId,
            UUID documentId,
            UUID chunkId,
            String sourceDigest,
            String text,
            List<Float> vector) {

        private SourceFixture {
            vector = List.copyOf(vector);
        }
    }
}
