package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildSourceCandidateRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface KnowledgeIndexPersistenceRepository {
    RetrievalPolicyRow insertPolicy(WorkspaceScope scope, RetrievalPolicyRow row);

    Optional<RetrievalPolicyRow> findPolicy(WorkspaceScope scope, UUID policyId);

    IndexRow insertIndex(WorkspaceScope scope, IndexRow row);

    Optional<IndexRow> findIndex(WorkspaceScope scope, UUID indexId);

    Optional<IndexRow> lockIndex(WorkspaceScope scope, UUID indexId);

    BuildRow insertBuild(WorkspaceScope scope, BuildRow row);

    Optional<BuildRow> findBuild(WorkspaceScope scope, UUID buildId);

    Optional<BuildRow> findBuildByIndexAndVersion(
            WorkspaceScope scope, UUID indexId, String requestedVersion);

    List<BuildRow> listBuilds(WorkspaceScope scope, UUID indexId);

    Optional<BuildRow> lockBuild(WorkspaceScope scope, UUID buildId);

    Optional<BuildRow> lockActiveBuildLease(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner,
            BuildStatus expectedStatus,
            BuildStep expectedStep);

    Optional<BuildRow> retryFailedBuild(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            OffsetDateTime retriedAt);

    Optional<BuildRow> cancelWaitingBuild(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            OffsetDateTime cancelledAt);

    List<BuildRow> claimBuilds(
            WorkspaceScope scope,
            String leaseOwner,
            Duration leaseDuration,
            int limit);

    Optional<BuildRow> renewBuildLease(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner,
            BuildStatus expectedStatus,
            BuildStep expectedStep,
            Duration leaseDuration);

    Optional<BuildRow> recordEmbeddingProgressAndRelease(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner,
            int embeddedEntryCount,
            int lastDurableChunkOrdinal);

    Optional<BuildRow> advanceBuildToIndexingAndRelease(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner);

    Optional<BuildRow> advanceBuildToValidatingAndRelease(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner,
            int validatedEntryCount,
            String validationDigest);

    Optional<BuildRow> failLeasedBuild(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner,
            BuildStatus expectedStatus,
            BuildStep expectedStep,
            BuildStatus failureStatus,
            boolean retryable,
            Duration retryDelay,
            String errorCode,
            String errorCategory,
            boolean reconciliationRequired);

    List<BuildSourceCandidateRow> listBuildSourceCandidates(
            WorkspaceScope scope,
            UUID knowledgeBaseId,
            List<UUID> sourceRevisionIds);

    BuildRevisionRow insertBuildRevision(WorkspaceScope scope, BuildRevisionRow row);

    List<BuildRevisionRow> listBuildRevisions(WorkspaceScope scope, UUID buildId);

    EntryRow insertEntry(WorkspaceScope scope, EntryRow row);

    List<EntryRow> listEntries(WorkspaceScope scope, UUID buildId);

    VersionRow insertVersion(WorkspaceScope scope, VersionRow row);

    VersionRow insertPublishedVersion(WorkspaceScope scope, VersionRow row);

    Optional<VersionRow> findVersion(WorkspaceScope scope, UUID versionId);

    List<VersionRow> listVersions(WorkspaceScope scope, UUID indexId);

    Optional<BuildRow> persistPublicationArtifact(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner,
            String expectedValidationDigest,
            String artifactDigest);

    Optional<BuildRow> completePublication(
            WorkspaceScope scope,
            UUID buildId,
            long expectedVersion,
            String expectedLeaseOwner,
            UUID publishedVersionId,
            int sourceCount,
            int chunkCount);

    Optional<IndexRow> recordPublishedVersion(
            WorkspaceScope scope,
            UUID indexId,
            long expectedMetadataVersion,
            int expectedVersionCount,
            UUID expectedLatestReadyVersionId,
            UUID publishedVersionId);
}
