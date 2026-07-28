package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingRouteCatalog;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ChunkRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.DocumentRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import java.util.ArrayList;
import java.util.List;

final class KnowledgeIndexArtifactAssembler {
    private final KnowledgePersistenceRepository knowledge;
    private final KnowledgeIndexPersistenceRepository indexes;
    private final EmbeddingRouteCatalog routes;
    private final KnowledgeIndexArtifactValidator validator;

    KnowledgeIndexArtifactAssembler(
            KnowledgePersistenceRepository knowledge,
            KnowledgeIndexPersistenceRepository indexes,
            EmbeddingRouteCatalog routes,
            KnowledgeIndexArtifactValidator validator) {
        this.knowledge = knowledge;
        this.indexes = indexes;
        this.routes = routes;
        this.validator = validator;
    }

    KnowledgeIndexArtifactManifest reconstruct(
            WorkspaceScope scope,
            BuildRow build) {
        EmbeddingRouteSnapshot route = routes.findEmbeddingRoute(
                        scope.workspaceId(), build.embeddingRouteId())
                .orElseThrow(() -> new IllegalStateException(
                        "APVERO_KNOWLEDGE_ARTIFACT_ROUTE_PROFILE_MISSING"));
        List<BuildRevisionRow> buildRevisions =
                indexes.listBuildRevisions(scope, build.id());
        List<SourceRevisionRow> sourceRevisions =
                new ArrayList<>(buildRevisions.size());
        List<DocumentRow> documents = new ArrayList<>();
        List<ChunkRow> chunks = new ArrayList<>();
        for (BuildRevisionRow buildRevision : buildRevisions) {
            knowledge.findRevision(scope, buildRevision.sourceRevisionId())
                    .ifPresent(sourceRevisions::add);
            documents.addAll(
                    knowledge.listDocuments(scope, buildRevision.sourceRevisionId()));
            chunks.addAll(
                    knowledge.listChunks(scope, buildRevision.sourceRevisionId()));
        }
        return validator.validate(new KnowledgeIndexArtifactEvidence(
                scope,
                build,
                route,
                buildRevisions,
                sourceRevisions,
                documents,
                chunks,
                indexes.listEntries(scope, build.id())));
    }
}
