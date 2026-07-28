package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.apvero.platform.capability.EmbeddingNormalization;
import io.apvero.platform.capability.EmbeddingRouteProfile;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.capability.ModelRouteCapability;
import io.apvero.platform.capability.ModelRouteStatus;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildSourceCandidateRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeIndexBuildDigestsTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID INDEX_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ROUTE_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Test
    void digestsAreStableAcrossLocaleAndTimeZone() {
        Locale originalLocale = Locale.getDefault();
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.US);
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            String firstSourceSet = KnowledgeIndexBuildDigests.sourceSet(sources());
            String firstRequest = KnowledgeIndexBuildDigests.request(scope(), index(), "1.2.3", route(), sources());

            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

            assertThat(KnowledgeIndexBuildDigests.sourceSet(sources())).isEqualTo(firstSourceSet);
            assertThat(KnowledgeIndexBuildDigests.request(scope(), index(), "1.2.3", route(), sources()))
                    .isEqualTo(firstRequest);
            assertThat(firstRequest).matches("^sha256:[a-f0-9]{64}$");
            assertThat(firstSourceSet)
                    .isEqualTo("sha256:ec8adf44e3eb6a4cb5b8c67f0af053532f19c276aea745b84d119d1e540e0d3a");
            assertThat(firstRequest)
                    .isEqualTo("sha256:45abc51168d811f9d5cc58dae1dcf3f0cf77922cda4f5795761b755d45304d6a");
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    void requestDigestChangesForEveryPinnedBuildInput() {
        String baseline = KnowledgeIndexBuildDigests.request(scope(), index(), "1.2.3", route(), sources());

        assertThat(KnowledgeIndexBuildDigests.request(scope(), index(), "1.2.4", route(), sources()))
                .isNotEqualTo(baseline);
        assertThat(KnowledgeIndexBuildDigests.request(
                        scope(), index(), "1.2.3", route(), List.of(changedChunkCount())))
                .isNotEqualTo(baseline);
    }

    private static WorkspaceScope scope() {
        return new WorkspaceScope(TENANT_ID, WORKSPACE_ID);
    }

    private static IndexRow index() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-27T00:00:00Z");
        return new IndexRow(
                INDEX_ID,
                TENANT_ID,
                WORKSPACE_ID,
                BASE_ID,
                "handbook",
                "Handbook",
                IndexStatus.ACTIVE,
                1,
                0,
                null,
                now,
                now);
    }

    private static EmbeddingRouteSnapshot route() {
        return new EmbeddingRouteSnapshot(
                ROUTE_ID,
                TENANT_ID,
                WORKSPACE_ID,
                "primary-embedding",
                7,
                UUID.fromString("00000000-0000-0000-0000-000000000006"),
                ModelRouteCapability.EMBEDDING,
                ModelRouteStatus.PUBLISHED,
                30_000,
                new EmbeddingRouteProfile(3, 8_192, 64, EmbeddingNormalization.NONE),
                true,
                "READY",
                OffsetDateTime.parse("2026-07-27T00:00:00Z"));
    }

    private static List<BuildSourceCandidateRow> sources() {
        return List.of(new BuildSourceCandidateRow(
                UUID.fromString("00000000-0000-0000-0000-000000000007"),
                UUID.fromString("00000000-0000-0000-0000-000000000008"),
                "sha256:source",
                "parser-v1",
                "chunker-v1",
                1,
                2));
    }

    private static BuildSourceCandidateRow changedChunkCount() {
        BuildSourceCandidateRow source = sources().getFirst();
        return new BuildSourceCandidateRow(
                source.sourceId(),
                source.sourceRevisionId(),
                source.sourceContentDigest(),
                source.parserVersion(),
                source.chunkerVersion(),
                source.documentCount(),
                source.chunkCount() + 1);
    }
}
