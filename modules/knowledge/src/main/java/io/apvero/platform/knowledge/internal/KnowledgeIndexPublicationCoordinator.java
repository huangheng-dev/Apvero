package io.apvero.platform.knowledge.internal;

import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPublicationCheckpoint.Stage;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPublicationOutcome.Status;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class KnowledgeIndexPublicationCoordinator {
    private static final String ACTOR = "apvero-index-build-runner";
    private static final String ACTION = "knowledge.index-version.published";
    private static final String RESOURCE_TYPE = "knowledge-index-version";

    private final KnowledgeIndexPersistenceRepository indexes;
    private final KnowledgeIndexArtifactAssembler artifacts;
    private final AuditEventCatalog auditEvents;
    private final KnowledgeIndexPublicationCheckpoint checkpoint;

    KnowledgeIndexPublicationCoordinator(
            KnowledgeIndexPersistenceRepository indexes,
            KnowledgeIndexArtifactAssembler artifacts,
            AuditEventCatalog auditEvents,
            KnowledgeIndexPublicationCheckpoint checkpoint) {
        this.indexes = indexes;
        this.artifacts = artifacts;
        this.auditEvents = auditEvents;
        this.checkpoint = checkpoint;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    KnowledgeIndexPublicationOutcome publish(
            WorkspaceScope scope,
            BuildRow claim,
            String leaseOwner) {
        BuildRow lockedBuild = indexes.lockBuild(scope, claim.id())
                .orElseThrow(() -> new IllegalStateException(
                        "APVERO_KNOWLEDGE_INDEX_BUILD_NOT_FOUND"));
        IndexRow lockedIndex = indexes.lockIndex(scope, lockedBuild.knowledgeIndexId())
                .orElseThrow(() -> new IllegalStateException(
                        "APVERO_KNOWLEDGE_INDEX_NOT_FOUND"));

        List<VersionRow> existingVersions =
                indexes.listVersions(scope, lockedIndex.id());
        requireConsistentIndex(lockedIndex, existingVersions);
        if (lockedBuild.status() == BuildStatus.READY) {
            return replay(
                    scope,
                    claim,
                    lockedBuild,
                    lockedIndex,
                    existingVersions);
        }

        requireClaimIdentity(claim, lockedBuild);
        requireActiveIndex(lockedIndex);
        BuildRow activeBuild = indexes.lockActiveBuildLease(
                        scope,
                        lockedBuild.id(),
                        claim.lockVersion(),
                        leaseOwner,
                        BuildStatus.VALIDATING,
                        BuildStep.VALIDATING)
                .orElseThrow(() -> new IllegalStateException(
                        "APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_STALE"));

        KnowledgeIndexArtifactManifest artifact =
                artifacts.reconstruct(scope, activeBuild);
        if (!artifact.validationDigest().equals(activeBuild.validationDigest())) {
            throw new IllegalStateException(
                    "APVERO_KNOWLEDGE_ARTIFACT_VALIDATION_DIGEST_DRIFT");
        }

        BuildRow artifactPersisted = indexes.persistPublicationArtifact(
                        scope,
                        activeBuild.id(),
                        activeBuild.lockVersion(),
                        leaseOwner,
                        artifact.validationDigest(),
                        artifact.artifactDigest())
                .orElseThrow(() -> new IllegalStateException(
                        "APVERO_KNOWLEDGE_INDEX_BUILD_CONCURRENT_MODIFICATION"));
        checkpoint.after(Stage.ARTIFACT_PERSISTED);

        UUID versionId = KnowledgeCanonicalDigests.stableId(
                "apvero:knowledge-index-version:" + activeBuild.id());
        VersionRow version = indexes.insertPublishedVersion(scope, new VersionRow(
                versionId,
                scope.tenantId(),
                scope.workspaceId(),
                lockedIndex.id(),
                activeBuild.id(),
                activeBuild.requestedVersion(),
                lockedIndex.slug() + "@" + activeBuild.requestedVersion(),
                activeBuild.embeddingRouteId(),
                activeBuild.embeddingRouteReference(),
                activeBuild.vectorDimension(),
                artifact.sourceCount(),
                artifact.chunkCount(),
                artifact.artifactDigest(),
                BuildStatus.READY.name(),
                null));
        checkpoint.after(Stage.VERSION_INSERTED);

        BuildRow readyBuild = indexes.completePublication(
                        scope,
                        activeBuild.id(),
                        artifactPersisted.lockVersion(),
                        leaseOwner,
                        version.id(),
                        artifact.sourceCount(),
                        artifact.chunkCount())
                .orElseThrow(() -> new IllegalStateException(
                        "APVERO_KNOWLEDGE_INDEX_BUILD_CONCURRENT_MODIFICATION"));
        checkpoint.after(Stage.BUILD_COMPLETED);
        IndexRow updatedIndex = indexes.recordPublishedVersion(
                        scope,
                        lockedIndex.id(),
                        lockedIndex.metadataVersion(),
                        lockedIndex.versionCount(),
                        lockedIndex.latestReadyVersionId(),
                        version.id())
                .orElseThrow(() -> new IllegalStateException(
                        "APVERO_KNOWLEDGE_INDEX_CONCURRENT_MODIFICATION"));
        checkpoint.after(Stage.INDEX_UPDATED);

        auditEvents.append(
                scope.workspaceId(),
                ACTOR,
                ACTION,
                RESOURCE_TYPE,
                version.id().toString(),
                "SUCCEEDED",
                null,
                "knowledge-publication-" + activeBuild.id());
        checkpoint.after(Stage.AUDIT_APPENDED);
        return new KnowledgeIndexPublicationOutcome(
                readyBuild, updatedIndex, version, Status.PUBLISHED);
    }

    private KnowledgeIndexPublicationOutcome replay(
            WorkspaceScope scope,
            BuildRow claim,
            BuildRow readyBuild,
            IndexRow index,
            List<VersionRow> versions) {
        requireReplayClaimIdentity(claim, readyBuild);
        UUID expectedVersionId = KnowledgeCanonicalDigests.stableId(
                "apvero:knowledge-index-version:" + readyBuild.id());
        VersionRow version = indexes.findVersion(scope, expectedVersionId)
                .orElseThrow(KnowledgeIndexPublicationCoordinator::publicationConflict);
        KnowledgeIndexArtifactManifest artifact;
        try {
            artifact = artifacts.reconstruct(scope, readyBuild);
        } catch (IllegalStateException exception) {
            throw publicationConflict();
        }
        boolean versionBelongsToIndex = versions.stream()
                .anyMatch(candidate -> candidate.id().equals(version.id()));
        boolean equal = versionBelongsToIndex
                && expectedVersionId.equals(readyBuild.publishedVersionId())
                && readyBuild.currentStep() == BuildStep.COMPLETE
                && readyBuild.requestedSourceCount() == artifact.sourceCount()
                && readyBuild.requestedChunkCount() == artifact.chunkCount()
                && readyBuild.embeddedEntryCount() == artifact.entryCount()
                && readyBuild.validatedEntryCount() == artifact.entryCount()
                && artifact.validationDigest().equals(readyBuild.validationDigest())
                && artifact.artifactDigest().equals(readyBuild.artifactDigest())
                && version.tenantId().equals(scope.tenantId())
                && version.workspaceId().equals(scope.workspaceId())
                && version.knowledgeIndexId().equals(index.id())
                && version.knowledgeIndexBuildId().equals(readyBuild.id())
                && version.version().equals(readyBuild.requestedVersion())
                && version.reference().equals(
                        index.slug() + "@" + readyBuild.requestedVersion())
                && version.embeddingRouteId().equals(artifact.embeddingRouteId())
                && version.embeddingRouteReference().equals(
                        artifact.embeddingRouteReference())
                && version.vectorDimension() == artifact.vectorDimension()
                && version.sourceCount() == artifact.sourceCount()
                && version.chunkCount() == artifact.chunkCount()
                && version.artifactDigest().equals(artifact.artifactDigest())
                && BuildStatus.READY.name().equals(version.status());
        if (!equal) {
            throw publicationConflict();
        }
        return new KnowledgeIndexPublicationOutcome(
                readyBuild, index, version, Status.REPLAYED);
    }

    private static void requireClaimIdentity(BuildRow claim, BuildRow locked) {
        if (!claim.tenantId().equals(locked.tenantId())
                || !claim.workspaceId().equals(locked.workspaceId())
                || !claim.knowledgeIndexId().equals(locked.knowledgeIndexId())
                || !claim.knowledgeBaseId().equals(locked.knowledgeBaseId())
                || !claim.requestDigest().equals(locked.requestDigest())
                || !claim.sourceSetDigest().equals(locked.sourceSetDigest())) {
            throw new IllegalStateException(
                    "APVERO_KNOWLEDGE_INDEX_BUILD_CLAIM_MISMATCH");
        }
    }

    private static void requireReplayClaimIdentity(
            BuildRow claim,
            BuildRow locked) {
        try {
            requireClaimIdentity(claim, locked);
        } catch (IllegalStateException exception) {
            throw publicationConflict();
        }
    }

    private static IllegalStateException publicationConflict() {
        return new IllegalStateException(
                "APVERO_KNOWLEDGE_PUBLICATION_CONFLICT");
    }

    private static void requireActiveIndex(IndexRow index) {
        if (index.status() != IndexStatus.ACTIVE) {
            throw new IllegalStateException(
                    "APVERO_KNOWLEDGE_INDEX_PUBLICATION_ARCHIVED");
        }
    }

    private static void requireConsistentIndex(
            IndexRow index,
            List<VersionRow> versions) {
        boolean pointerValid = index.latestReadyVersionId() == null
                ? versions.isEmpty()
                : versions.stream().anyMatch(version ->
                        version.id().equals(index.latestReadyVersionId())
                                && "READY".equals(version.status()));
        boolean versionsReady = versions.stream()
                .allMatch(version -> "READY".equals(version.status()));
        if (index.versionCount() != versions.size()
                || !pointerValid
                || !versionsReady) {
            throw new IllegalStateException(
                    "APVERO_KNOWLEDGE_INDEX_PUBLICATION_CONFLICT");
        }
    }
}
