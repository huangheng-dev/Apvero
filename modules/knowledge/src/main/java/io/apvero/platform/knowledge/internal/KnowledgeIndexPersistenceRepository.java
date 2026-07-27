package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildSourceCandidateRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
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

    List<BuildSourceCandidateRow> listBuildSourceCandidates(
            WorkspaceScope scope,
            UUID knowledgeBaseId,
            List<UUID> sourceRevisionIds);

    BuildRevisionRow insertBuildRevision(WorkspaceScope scope, BuildRevisionRow row);

    List<BuildRevisionRow> listBuildRevisions(WorkspaceScope scope, UUID buildId);

    EntryRow insertEntry(WorkspaceScope scope, EntryRow row);

    List<EntryRow> listEntries(WorkspaceScope scope, UUID buildId);

    VersionRow insertVersion(WorkspaceScope scope, VersionRow row);

    Optional<VersionRow> findVersion(WorkspaceScope scope, UUID versionId);
}
