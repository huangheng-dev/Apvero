package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeIndexVersion;
import io.apvero.platform.knowledge.KnowledgeIndexVersionCatalog;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultKnowledgeIndexVersionCatalog implements KnowledgeIndexVersionCatalog {
    private final KnowledgeAvailability availability;
    private final WorkspaceScopeCatalog workspaces;
    private final KnowledgeIndexPersistenceRepository versions;

    public DefaultKnowledgeIndexVersionCatalog(
            KnowledgeAvailability availability,
            WorkspaceScopeCatalog workspaces,
            KnowledgeIndexPersistenceRepository versions) {
        this.availability = availability;
        this.workspaces = workspaces;
        this.versions = versions;
    }

    @Override
    public List<KnowledgeIndexVersion> list(UUID workspaceId, UUID indexId) {
        WorkspaceScope scope = scope(workspaceId);
        return (indexId == null ? versions.listVersions(scope) : versions.listVersions(scope, indexId))
                .stream()
                .map(DefaultKnowledgeIndexVersionCatalog::map)
                .toList();
    }

    @Override
    public KnowledgeIndexVersion get(UUID workspaceId, UUID indexVersionId) {
        WorkspaceScope scope = scope(workspaceId);
        if (indexVersionId == null) {
            throw problem(
                    "APVERO_KNOWLEDGE_IDENTIFIER_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        return versions.findVersion(scope, indexVersionId)
                .map(DefaultKnowledgeIndexVersionCatalog::map)
                .orElseThrow(() -> problem(
                        "APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND",
                        KnowledgeException.Category.NOT_FOUND));
    }

    @Override
    public KnowledgeIndexVersion getByReference(UUID workspaceId, String reference) {
        WorkspaceScope scope = scope(workspaceId);
        if (reference == null || reference.isBlank() || reference.length() > 240) {
            throw problem(
                    "APVERO_KNOWLEDGE_INDEX_VERSION_REFERENCE_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        return versions.findVersionByReference(scope, reference.trim())
                .map(DefaultKnowledgeIndexVersionCatalog::map)
                .orElseThrow(() -> problem(
                        "APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND",
                        KnowledgeException.Category.NOT_FOUND));
    }

    private WorkspaceScope scope(UUID workspaceId) {
        availability.requireEnabled();
        if (workspaceId == null) {
            throw problem(
                    "APVERO_KNOWLEDGE_IDENTIFIER_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        return workspaces.require(workspaceId);
    }

    private static KnowledgeIndexVersion map(VersionRow row) {
        return new KnowledgeIndexVersion(
                row.id(),
                row.tenantId(),
                row.workspaceId(),
                row.knowledgeIndexId(),
                row.knowledgeIndexBuildId(),
                row.version(),
                row.reference(),
                row.embeddingRouteId(),
                row.embeddingRouteReference(),
                row.vectorDimension(),
                row.sourceCount(),
                row.chunkCount(),
                row.artifactDigest(),
                KnowledgeIndexVersion.Status.valueOf(row.status()),
                row.publishedAt());
    }

    private static KnowledgeException problem(
            String code, KnowledgeException.Category category) {
        return new KnowledgeException(code, category);
    }
}
