package io.apvero.platform.knowledge.internal;

import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
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

    KnowledgeIndexPublicationCoordinator(
            KnowledgeIndexPersistenceRepository indexes,
            KnowledgeIndexArtifactAssembler artifacts,
            AuditEventCatalog auditEvents) {
        this.indexes = indexes;
        this.artifacts = artifacts;
        this.auditEvents = auditEvents;
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

        requireClaimIdentity(claim, lockedBuild);
        requireActiveIndex(lockedIndex);
        List<VersionRow> existingVersions =
                indexes.listVersions(scope, lockedIndex.id());
        requireConsistentIndex(lockedIndex, existingVersions);

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
        IndexRow updatedIndex = indexes.recordPublishedVersion(
                        scope,
                        lockedIndex.id(),
                        lockedIndex.metadataVersion(),
                        lockedIndex.versionCount(),
                        lockedIndex.latestReadyVersionId(),
                        version.id())
                .orElseThrow(() -> new IllegalStateException(
                        "APVERO_KNOWLEDGE_INDEX_CONCURRENT_MODIFICATION"));

        auditEvents.append(
                scope.workspaceId(),
                ACTOR,
                ACTION,
                RESOURCE_TYPE,
                version.id().toString(),
                "SUCCEEDED",
                null,
                "knowledge-publication-" + activeBuild.id());
        return new KnowledgeIndexPublicationOutcome(readyBuild, updatedIndex, version);
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
