package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.ExactRetrievalCandidate;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ExactKnowledgeRetrievalKernel {
    private final KnowledgeIndexPersistenceRepository repository;

    ExactKnowledgeRetrievalKernel(KnowledgeIndexPersistenceRepository repository) {
        this.repository = repository;
    }

    List<ExactRetrievalCandidate> retrieve(
            WorkspaceScope scope,
            UUID indexVersionId,
            List<Float> queryEmbedding,
            double minimumScore,
            int topK) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(indexVersionId, "indexVersionId");
        if (topK < 1 || topK > 100) {
            throw problem("APVERO_KNOWLEDGE_RETRIEVAL_TOP_K_INVALID");
        }
        if (!Double.isFinite(minimumScore) || minimumScore < 0.0 || minimumScore > 1.0) {
            throw problem("APVERO_KNOWLEDGE_RETRIEVAL_MINIMUM_SCORE_INVALID");
        }

        VersionRow version = repository.findVersion(scope, indexVersionId)
                .filter(row -> "READY".equals(row.status()))
                .orElseThrow(() -> new KnowledgeException(
                        "APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND",
                        KnowledgeException.Category.NOT_FOUND));
        requireVector(queryEmbedding, version.vectorDimension());

        return repository.rankExact(
                scope,
                version.id(),
                queryEmbedding,
                version.vectorDimension(),
                minimumScore,
                topK);
    }

    private static void requireVector(List<Float> vector, int expectedDimension) {
        if (vector == null || vector.size() != expectedDimension) {
            throw problem("APVERO_KNOWLEDGE_RETRIEVAL_VECTOR_DIMENSION_MISMATCH");
        }
        double squaredNorm = 0.0;
        for (Float value : vector) {
            if (value == null || !Float.isFinite(value)) {
                throw problem("APVERO_KNOWLEDGE_RETRIEVAL_VECTOR_INVALID");
            }
            squaredNorm += (double) value * value;
        }
        if (!Double.isFinite(squaredNorm) || squaredNorm <= 0.0) {
            throw problem("APVERO_KNOWLEDGE_RETRIEVAL_VECTOR_INVALID");
        }
    }

    private static KnowledgeException problem(String code) {
        return new KnowledgeException(code, KnowledgeException.Category.BAD_REQUEST);
    }
}
