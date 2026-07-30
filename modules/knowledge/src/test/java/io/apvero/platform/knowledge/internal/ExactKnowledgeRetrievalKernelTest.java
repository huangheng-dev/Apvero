package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExactKnowledgeRetrievalKernelTest {
    private final KnowledgeIndexPersistenceRepository repository =
            mock(KnowledgeIndexPersistenceRepository.class);
    private final ExactKnowledgeRetrievalKernel kernel =
            new ExactKnowledgeRetrievalKernel(repository);
    private final WorkspaceScope scope =
            new WorkspaceScope(UUID.randomUUID(), UUID.randomUUID());
    private final UUID versionId = UUID.randomUUID();

    @Test
    void delegatesValidatedVectorAndPolicyBoundsWithoutResorting() {
        VersionRow version = version(3);
        when(repository.findVersion(scope, versionId)).thenReturn(Optional.of(version));
        when(repository.rankExact(scope, versionId, List.of(1.0F, 0.0F, 0.0F), 3, 0.7, 5))
                .thenReturn(List.of());

        assertThat(kernel.retrieve(
                scope, versionId, List.of(1.0F, 0.0F, 0.0F), 0.7, 5)).isEmpty();

        verify(repository).rankExact(
                scope, versionId, List.of(1.0F, 0.0F, 0.0F), 3, 0.7, 5);
    }

    @Test
    void rejectsInvalidPolicyBoundsBeforeVersionLookup() {
        assertBadRequest(
                () -> kernel.retrieve(scope, versionId, List.of(1.0F), 0.0, 0),
                "APVERO_KNOWLEDGE_RETRIEVAL_TOP_K_INVALID");
        assertBadRequest(
                () -> kernel.retrieve(scope, versionId, List.of(1.0F), Double.NaN, 1),
                "APVERO_KNOWLEDGE_RETRIEVAL_MINIMUM_SCORE_INVALID");
        assertBadRequest(
                () -> kernel.retrieve(scope, versionId, List.of(1.0F), 1.01, 1),
                "APVERO_KNOWLEDGE_RETRIEVAL_MINIMUM_SCORE_INVALID");

        verify(repository, never()).rankExact(
                scope, versionId, List.of(1.0F), 1, 0.0, 1);
    }

    @Test
    void rejectsMissingWrongDimensionNonFiniteAndZeroVectorsBeforeRanking() {
        when(repository.findVersion(scope, versionId)).thenReturn(Optional.of(version(3)));

        assertBadRequest(
                () -> kernel.retrieve(scope, versionId, List.of(1.0F, 0.0F), 0.0, 1),
                "APVERO_KNOWLEDGE_RETRIEVAL_VECTOR_DIMENSION_MISMATCH");
        assertBadRequest(
                () -> kernel.retrieve(
                        scope,
                        versionId,
                        Arrays.asList(1.0F, Float.NaN, 0.0F),
                        0.0,
                        1),
                "APVERO_KNOWLEDGE_RETRIEVAL_VECTOR_INVALID");
        assertBadRequest(
                () -> kernel.retrieve(scope, versionId, List.of(0.0F, -0.0F, 0.0F), 0.0, 1),
                "APVERO_KNOWLEDGE_RETRIEVAL_VECTOR_INVALID");
    }

    @Test
    void hidesMissingOrDifferentlyScopedVersionBehindStableNotFound() {
        when(repository.findVersion(scope, versionId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> kernel.retrieve(scope, versionId, List.of(1.0F), 0.0, 1))
                .isInstanceOf(KnowledgeException.class)
                .satisfies(error -> assertThat(((KnowledgeException) error).code())
                        .isEqualTo("APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND"));
    }

    @Test
    void sqlContractKeepsScopeRankingLimitAndHistoryRulesInsideOneStatement() {
        assertThat(JooqKnowledgeIndexPersistenceRepository.EXACT_RETRIEVAL_SQL)
                .contains("version.tenant_id = ?")
                .contains("version.workspace_id = ?")
                .contains("version.id = ?")
                .contains("version.status = 'READY'")
                .contains("entry.vector_dimension = version.vector_dimension")
                .contains("vector_dims(query_input.embedding) = version.vector_dimension")
                .contains("order by cosine_distance asc, entry.chunk_id asc")
                .contains("limit ?")
                .doesNotContain("source.status");
    }

    private VersionRow version(int dimension) {
        return new VersionRow(
                versionId,
                scope.tenantId(),
                scope.workspaceId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "1.0.0",
                "index@1.0.0",
                UUID.randomUUID(),
                "route@1",
                dimension,
                1,
                1,
                "sha256:" + "a".repeat(64),
                "READY",
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static void assertBadRequest(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(KnowledgeException.class)
                .satisfies(error -> {
                    KnowledgeException problem = (KnowledgeException) error;
                    assertThat(problem.code()).isEqualTo(code);
                    assertThat(problem.category()).isEqualTo(KnowledgeException.Category.BAD_REQUEST);
                });
    }
}
