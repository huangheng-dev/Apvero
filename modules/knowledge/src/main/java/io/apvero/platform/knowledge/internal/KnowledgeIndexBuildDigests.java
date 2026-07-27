package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildSourceCandidateRow;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

final class KnowledgeIndexBuildDigests {
    private static final String SHA256_PREFIX = "sha256:";

    private KnowledgeIndexBuildDigests() {}

    static String sourceSet(List<BuildSourceCandidateRow> orderedSources) {
        DigestBuilder digest = new DigestBuilder("apvero-knowledge-source-set-v1");
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
        DigestBuilder digest = new DigestBuilder("apvero-knowledge-index-build-request-v1");
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

    private static final class DigestBuilder {
        private final MessageDigest digest = sha256();

        private DigestBuilder(String format) {
            addString(format);
        }

        private void addUuid(UUID value) {
            ByteBuffer bytes = ByteBuffer.allocate(2 * Long.BYTES);
            bytes.putLong(value.getMostSignificantBits());
            bytes.putLong(value.getLeastSignificantBits());
            add(bytes.array());
        }

        private void addInt(int value) {
            add(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
        }

        private void addString(String value) {
            add(value.getBytes(StandardCharsets.UTF_8));
        }

        private void add(byte[] value) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
            digest.update(value);
        }

        private String finish() {
            return SHA256_PREFIX + HexFormat.of().formatHex(digest.digest());
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("APVERO_SHA256_UNAVAILABLE", exception);
            }
        }
    }
}
