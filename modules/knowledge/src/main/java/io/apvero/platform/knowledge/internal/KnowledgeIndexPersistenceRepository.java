package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.EntryRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface KnowledgeIndexPersistenceRepository {
    RetrievalPolicyRow insertPolicy(WorkspaceScope scope, RetrievalPolicyRow row);

    Optional<RetrievalPolicyRow> findPolicy(WorkspaceScope scope, UUID policyId);

    IndexRow insertIndex(WorkspaceScope scope, IndexRow row);

    Optional<IndexRow> findIndex(WorkspaceScope scope, UUID indexId);

    BuildRow insertBuild(WorkspaceScope scope, BuildRow row);

    Optional<BuildRow> findBuild(WorkspaceScope scope, UUID buildId);

    BuildRevisionRow insertBuildRevision(WorkspaceScope scope, BuildRevisionRow row);

    List<BuildRevisionRow> listBuildRevisions(WorkspaceScope scope, UUID buildId);

    EntryRow insertEntry(WorkspaceScope scope, EntryRow row);

    List<EntryRow> listEntries(WorkspaceScope scope, UUID buildId);

    VersionRow insertVersion(WorkspaceScope scope, VersionRow row);

    Optional<VersionRow> findVersion(WorkspaceScope scope, UUID versionId);
}
