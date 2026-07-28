package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeCanonicalDigests.DigestBuilder;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildSourceCandidateRow;
import java.util.List;

final class KnowledgeIndexBuildDigests {
    private KnowledgeIndexBuildDigests() {}

    static String sourceSet(List<BuildSourceCandidateRow> orderedSources) {
        DigestBuilder digest = KnowledgeCanonicalDigests.builder("apvero-knowledge-source-set-v1");
        digest.addInt(orderedSources.size());
        for (BuildSourceCandidateRow source : orderedSources) {
            digest.addUuid(source.sourceId());
            digest.addUuid(source.sourceRevisionId());
            digest.addString(source.sourceContentDigest());
            digest.addString(source.parserVersion());
            digest.addString(source.chunkerVersion());
            digest.addInt(source.chunkCount());
        }
        return digest.finish();
    }

    static String request(
            WorkspaceScope scope,
            KnowledgeIndexPersistenceRecords.IndexRow index,
            String version,
            EmbeddingRouteSnapshot route,
            List<BuildSourceCandidateRow> orderedSources) {
        DigestBuilder digest =
                KnowledgeCanonicalDigests.builder("apvero-knowledge-index-build-request-v1");
        digest.addUuid(scope.tenantId());
        digest.addUuid(scope.workspaceId());
        digest.addUuid(index.id());
        digest.addUuid(index.knowledgeBaseId());
        digest.addString(version);
        digest.addUuid(route.id());
        digest.addString(route.reference());
        digest.addInt(route.profile().dimension());
        digest.addInt(route.profile().maximumInputTokens());
        digest.addInt(route.profile().maximumBatchSize());
        digest.addString(route.profile().normalization().name());
        digest.addInt(orderedSources.size());
        for (BuildSourceCandidateRow source : orderedSources) {
            digest.addUuid(source.sourceId());
            digest.addUuid(source.sourceRevisionId());
            digest.addString(source.sourceContentDigest());
            digest.addString(source.parserVersion());
            digest.addString(source.chunkerVersion());
            digest.addInt(source.chunkCount());
        }
        return digest.finish();
    }
}
