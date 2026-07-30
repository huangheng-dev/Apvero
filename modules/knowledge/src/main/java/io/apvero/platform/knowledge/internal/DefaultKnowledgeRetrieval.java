package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingInputUnitEstimator;
import io.apvero.platform.governance.RetentionPolicy;
import io.apvero.platform.governance.RetentionPolicyCatalog;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeRetrieval;
import io.apvero.platform.knowledge.KnowledgeRetrievalHit;
import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import io.apvero.platform.knowledge.KnowledgeSource;
import io.apvero.platform.knowledge.RetrievalPolicyOverlapHandling;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.ExactRetrievalCandidate;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
final class DefaultKnowledgeRetrieval implements KnowledgeRetrieval {
    private static final String NO_EVIDENCE = "NO_EVIDENCE";

    private final GovernedKnowledgeRetrievalExecutor executor;
    private final RetentionPolicyCatalog retentionPolicies;
    private final EmbeddingInputUnitEstimator tokenEstimator;
    private final KnowledgeRetrievalTelemetry telemetry;

    DefaultKnowledgeRetrieval(
            GovernedKnowledgeRetrievalExecutor executor,
            RetentionPolicyCatalog retentionPolicies,
            EmbeddingInputUnitEstimator tokenEstimator,
            KnowledgeRetrievalTelemetry telemetry) {
        this.executor = executor;
        this.retentionPolicies = retentionPolicies;
        this.tokenEstimator = tokenEstimator;
        this.telemetry = telemetry;
    }

    @Override
    public KnowledgeRetrievalResult retrieve(
            UUID workspaceId,
            KnowledgeCommandContext context,
            UUID indexVersionId,
            UUID retrievalPolicyVersionId,
            String query) {
        long startedAt = System.nanoTime();
        try {
            GovernedRetrievalExecution execution = executor.execute(
                    workspaceId, context, indexVersionId, retrievalPolicyVersionId, query);
            requireSupportedPolicy(execution);

            List<ExactRetrievalCandidate> overlapAccepted =
                    applyOverlap(execution.rankedCandidates(), execution.retrievalPolicy());
            RetentionPolicy retention = retentionPolicies.get(workspaceId);
            requireCurrentRetention(execution, retention);
            boolean discloseContent =
                    retention.retainPayloads() && !retention.maskSensitiveFields();
            List<KnowledgeRetrievalHit> hits = applyBudgetAndProject(
                    overlapAccepted, execution.retrievalPolicy(), discloseContent);
            long elapsedNanos = Math.max(0, System.nanoTime() - startedAt);
            KnowledgeRetrievalResult.Status status = hits.isEmpty()
                    ? KnowledgeRetrievalResult.Status.NO_EVIDENCE
                    : KnowledgeRetrievalResult.Status.MATCHES;
            KnowledgeRetrievalResult result = new KnowledgeRetrievalResult(
                    status,
                    execution.indexVersion().id(),
                    execution.retrievalPolicy().id(),
                    execution.queryDigest(),
                    hits,
                    TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
            telemetry.succeeded(
                    status,
                    elapsedNanos,
                    execution.providerLatencyMillis(),
                    execution.rankedCandidates().size(),
                    hits);
            return result;
        } catch (RuntimeException failure) {
            telemetry.failed(failure, Math.max(0, System.nanoTime() - startedAt));
            throw failure;
        }
    }

    private void requireSupportedPolicy(GovernedRetrievalExecution execution) {
        RetrievalPolicyRow policy = execution.retrievalPolicy();
        boolean supported = DefaultRetrievalPolicyVersionCatalog.RETRIEVAL_ALGORITHM_VERSION
                        .equals(policy.retrievalAlgorithmVersion())
                && DefaultRetrievalPolicyVersionCatalog.TOKEN_ESTIMATOR_VERSION
                        .equals(policy.tokenEstimatorVersion())
                && DefaultRetrievalPolicyVersionCatalog.TOKEN_ESTIMATOR_IMPLEMENTATION_VERSION
                        .equals(tokenEstimator.algorithmVersion())
                && NO_EVIDENCE.equals(policy.noEvidenceBehavior())
                && policy.retentionPolicyVersionAtPublish() >= 1
                && policy.topK() >= 1
                && policy.topK() <= 100
                && policy.maximumContextInputUnits() >= 128
                && policy.maximumContextInputUnits() <= 200_000
                && policy.minimumScore() != null
                && policy.minimumScore().compareTo(BigDecimal.ZERO) >= 0
                && policy.minimumScore().compareTo(BigDecimal.ONE) <= 0
                && execution.indexVersion().tenantId().equals(policy.tenantId())
                && execution.indexVersion().workspaceId().equals(policy.workspaceId())
                && execution.rankedCandidates().size() <= policy.topK();
        if (!supported) {
            throw problem("APVERO_KNOWLEDGE_RETRIEVAL_POLICY_INTEGRITY_INVALID");
        }
        try {
            RetrievalPolicyOverlapHandling.valueOf(policy.overlapBehavior());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw problem("APVERO_KNOWLEDGE_RETRIEVAL_POLICY_INTEGRITY_INVALID");
        }
        String digest = RetrievalPolicyDigests.canonical(
                policy.retrievalAlgorithmVersion(),
                policy.tokenEstimatorVersion(),
                policy.retentionPolicyVersionAtPublish(),
                policy.topK(),
                policy.maximumContextInputUnits(),
                policy.minimumScore(),
                policy.overlapBehavior(),
                policy.noEvidenceBehavior());
        if (!digest.equals(policy.policyDigest())) {
            throw problem("APVERO_KNOWLEDGE_RETRIEVAL_POLICY_INTEGRITY_INVALID");
        }
    }

    private static void requireCurrentRetention(
            GovernedRetrievalExecution execution, RetentionPolicy retention) {
        if (retention == null
                || retention.version() < 0
                || !execution.indexVersion().tenantId().equals(retention.tenantId())
                || !execution.indexVersion().workspaceId().equals(retention.workspaceId())) {
            throw problem("APVERO_KNOWLEDGE_RETENTION_POLICY_INVALID");
        }
    }

    private static List<ExactRetrievalCandidate> applyOverlap(
            List<ExactRetrievalCandidate> ranked, RetrievalPolicyRow policy) {
        for (int index = 0; index < ranked.size(); index++) {
            if (ranked.get(index).rank() != index + 1) {
                throw problem("APVERO_KNOWLEDGE_RETRIEVAL_EVIDENCE_INVALID");
            }
        }
        if (RetrievalPolicyOverlapHandling.KEEP.name().equals(policy.overlapBehavior())) {
            return ranked;
        }
        List<ExactRetrievalCandidate> accepted = new ArrayList<>();
        for (ExactRetrievalCandidate candidate : ranked) {
            boolean overlaps = accepted.stream().anyMatch(existing ->
                    existing.documentId().equals(candidate.documentId())
                            && rangesOverlap(existing, candidate));
            if (!overlaps) {
                accepted.add(candidate);
            }
        }
        return List.copyOf(accepted);
    }

    private List<KnowledgeRetrievalHit> applyBudgetAndProject(
            List<ExactRetrievalCandidate> candidates,
            RetrievalPolicyRow policy,
            boolean discloseContent) {
        long remaining = policy.maximumContextInputUnits();
        List<KnowledgeRetrievalHit> hits = new ArrayList<>();
        for (ExactRetrievalCandidate candidate : candidates) {
            String content = discloseContent ? candidate.content() : null;
            long units = content == null ? 0 : tokenEstimator.estimateUnits(content);
            if (content != null && units < 1) {
                throw problem("APVERO_KNOWLEDGE_TOKEN_ESTIMATOR_UNSUPPORTED");
            }
            if (units > remaining) {
                continue;
            }
            remaining -= units;
            hits.add(project(candidate, hits.size() + 1, content));
        }
        return List.copyOf(hits);
    }

    private static KnowledgeRetrievalHit project(
            ExactRetrievalCandidate candidate, int rank, String content) {
        KnowledgeSource.Type sourceType;
        try {
            sourceType = KnowledgeSource.Type.valueOf(candidate.sourceType());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw problem("APVERO_KNOWLEDGE_RETRIEVAL_EVIDENCE_INVALID");
        }
        try {
            return new KnowledgeRetrievalHit(
                    rank,
                    candidate.score(),
                    candidate.sourceId(),
                    candidate.sourceRevisionId(),
                    candidate.documentId(),
                    candidate.chunkId(),
                    candidate.contentDigest(),
                    content,
                    candidate.sourceTitle(),
                    sourceType,
                    candidate.page(),
                    candidate.heading(),
                    candidate.paragraph(),
                    candidate.lineStart(),
                    candidate.lineEnd());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw problem("APVERO_KNOWLEDGE_RETRIEVAL_EVIDENCE_INVALID");
        }
    }

    private static boolean rangesOverlap(
            ExactRetrievalCandidate left, ExactRetrievalCandidate right) {
        return left.startOffset() < right.endOffset()
                && right.startOffset() < left.endOffset();
    }

    private static KnowledgeException problem(String code) {
        return new KnowledgeException(code, KnowledgeException.Category.CONFLICT);
    }
}
