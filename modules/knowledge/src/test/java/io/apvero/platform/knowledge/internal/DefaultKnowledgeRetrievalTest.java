package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.apvero.platform.capability.EmbeddingInputUnitEstimator;
import io.apvero.platform.governance.RetentionPolicy;
import io.apvero.platform.governance.RetentionPolicyCatalog;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeRetrievalHit;
import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import io.apvero.platform.knowledge.RetrievalPolicyOverlapHandling;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.ExactRetrievalCandidate;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultKnowledgeRetrievalTest {
    private final GovernedKnowledgeRetrievalExecutor executor =
            mock(GovernedKnowledgeRetrievalExecutor.class);
    private final RetentionPolicyCatalog retention = mock(RetentionPolicyCatalog.class);
    private final Utf8Estimator estimator = new Utf8Estimator();
    private final KnowledgeRetrievalTelemetry telemetry =
            new KnowledgeRetrievalTelemetry(new SimpleMeterRegistry());
    private final DefaultKnowledgeRetrieval retrieval =
            new DefaultKnowledgeRetrieval(executor, retention, estimator, telemetry);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID indexVersionId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final KnowledgeCommandContext context =
            new KnowledgeCommandContext("maintainer", "127.0.0.1", "trace-e4");

    @BeforeEach
    void setUp() {
        when(retention.get(workspaceId)).thenReturn(retention(true, false));
    }

    @Test
    void keepsSqlOrderSkipsOversizedContentAndAdmitsLaterUnicodeHit() {
        RetrievalPolicyRow policy = policy(
                128, RetrievalPolicyOverlapHandling.KEEP);
        ExactRetrievalCandidate oversized =
                candidate(1, UUID.randomUUID(), 0, 10, "x".repeat(129));
        String later = "中文".repeat(20) + "12345678";
        ExactRetrievalCandidate fitting =
                candidate(2, UUID.randomUUID(), 0, 10, later);
        stub(policy, List.of(oversized, fitting));

        KnowledgeRetrievalResult result = retrieve();

        assertThat(result.status()).isEqualTo(KnowledgeRetrievalResult.Status.MATCHES);
        assertThat(result.hits()).extracting(KnowledgeRetrievalHit::chunkId)
                .containsExactly(fitting.chunkId());
        assertThat(result.hits().getFirst().rank()).isEqualTo(1);
        assertThat(result.hits().getFirst().content()).isEqualTo(later);
    }

    @Test
    void collapsesIntersectingRangesOnlyWithinTheSameImmutableDocument() {
        RetrievalPolicyRow policy = policy(
                1_000, RetrievalPolicyOverlapHandling.COLLAPSE_ADJACENT);
        UUID document = UUID.randomUUID();
        ExactRetrievalCandidate first = candidate(1, document, 0, 10, "first");
        ExactRetrievalCandidate overlapping = candidate(2, document, 5, 15, "second");
        ExactRetrievalCandidate touching = candidate(3, document, 10, 20, "third");
        ExactRetrievalCandidate otherDocument =
                candidate(4, UUID.randomUUID(), 5, 15, "fourth");
        stub(policy, List.of(first, overlapping, touching, otherDocument));

        KnowledgeRetrievalResult result = retrieve();

        assertThat(result.hits()).extracting(KnowledgeRetrievalHit::chunkId)
                .containsExactly(first.chunkId(), touching.chunkId(), otherDocument.chunkId());
        assertThat(result.hits()).extracting(KnowledgeRetrievalHit::rank)
                .containsExactly(1, 2, 3);
    }

    @Test
    void suppressesContentAndConsumesNoBudgetWhenPayloadRetentionIsDisabled() {
        when(retention.get(workspaceId)).thenReturn(retention(false, false));
        RetrievalPolicyRow policy = policy(
                128, RetrievalPolicyOverlapHandling.KEEP);
        ExactRetrievalCandidate first =
                candidate(1, UUID.randomUUID(), 0, 10, "x".repeat(10_000));
        ExactRetrievalCandidate second =
                candidate(2, UUID.randomUUID(), 0, 10, "y".repeat(10_000));
        stub(policy, List.of(first, second));

        KnowledgeRetrievalResult result = retrieve();

        assertThat(result.hits()).hasSize(2).allSatisfy(hit -> assertThat(hit.content()).isNull());
        assertThat(estimator.calls).isZero();
    }

    @Test
    void suppressesContentWhenMaskingIsRequiredBecauseNoMaskerIsApproved() {
        when(retention.get(workspaceId)).thenReturn(retention(true, true));
        RetrievalPolicyRow policy = policy(
                128, RetrievalPolicyOverlapHandling.KEEP);
        stub(policy, List.of(candidate(1, UUID.randomUUID(), 0, 10, "secret")));

        KnowledgeRetrievalResult result = retrieve();

        assertThat(result.hits().getFirst().content()).isNull();
        assertThat(estimator.calls).isZero();
    }

    @Test
    void returnsTypedNoEvidenceForAnEmptyRankedSet() {
        stub(policy(128, RetrievalPolicyOverlapHandling.KEEP), List.of());

        KnowledgeRetrievalResult result = retrieve();

        assertThat(result.status()).isEqualTo(KnowledgeRetrievalResult.Status.NO_EVIDENCE);
        assertThat(result.hits()).isEmpty();
        assertThat(result.queryDigest()).matches("^sha256:[a-f0-9]{64}$");
    }

    @Test
    void rejectsUnsupportedOrTamperedPolicyBeforeReadingCurrentRetention() {
        RetrievalPolicyRow valid = policy(128, RetrievalPolicyOverlapHandling.KEEP);
        RetrievalPolicyRow tampered = new RetrievalPolicyRow(
                valid.id(),
                valid.tenantId(),
                valid.workspaceId(),
                valid.slug(),
                valid.version(),
                valid.retrievalAlgorithmVersion(),
                valid.tokenEstimatorVersion(),
                valid.retentionPolicyVersionAtPublish(),
                valid.topK(),
                valid.maximumContextInputUnits(),
                valid.minimumScore(),
                valid.overlapBehavior(),
                valid.noEvidenceBehavior(),
                "sha256:" + "0".repeat(64),
                valid.createdBy(),
                valid.createdAt());
        stub(tampered, List.of());

        assertThatThrownBy(this::retrieve)
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_RETRIEVAL_POLICY_INTEGRITY_INVALID");
        verifyNoInteractions(retention);
    }

    @Test
    void acceptsTwentyThousandUnicodeCodePointsAndRejectsTheNextBoundary() {
        String exact = "😀".repeat(20_000);
        KnowledgeRetrievalHit hit = publicHit(exact);

        assertThat(hit.content()).isEqualTo(exact);
        assertThatThrownBy(() -> publicHit(exact + "😀"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_KNOWLEDGE_RETRIEVAL_HIT_INVALID");
    }

    private KnowledgeRetrievalResult retrieve() {
        return retrieval.retrieve(
                workspaceId, context, indexVersionId, policyId, "What is the policy?");
    }

    private void stub(RetrievalPolicyRow policy, List<ExactRetrievalCandidate> candidates) {
        when(executor.execute(
                        workspaceId,
                        context,
                        indexVersionId,
                        policyId,
                        "What is the policy?"))
                .thenReturn(new GovernedRetrievalExecution(
                        version(), policy, "sha256:" + "a".repeat(64), candidates, 4));
    }

    private VersionRow version() {
        return new VersionRow(
                indexVersionId,
                tenantId,
                workspaceId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "1.0.0",
                "support@1.0.0",
                UUID.randomUUID(),
                "embedding@1.0.0",
                3,
                1,
                4,
                "sha256:" + "b".repeat(64),
                "READY",
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private RetrievalPolicyRow policy(
            int maximumContextUnits, RetrievalPolicyOverlapHandling overlap) {
        String digest = RetrievalPolicyDigests.canonical(
                DefaultRetrievalPolicyVersionCatalog.RETRIEVAL_ALGORITHM_VERSION,
                DefaultRetrievalPolicyVersionCatalog.TOKEN_ESTIMATOR_VERSION,
                1,
                8,
                maximumContextUnits,
                new BigDecimal("0.5"),
                overlap.name(),
                "NO_EVIDENCE");
        return new RetrievalPolicyRow(
                policyId,
                tenantId,
                workspaceId,
                "support",
                "1.0.0",
                DefaultRetrievalPolicyVersionCatalog.RETRIEVAL_ALGORITHM_VERSION,
                DefaultRetrievalPolicyVersionCatalog.TOKEN_ESTIMATOR_VERSION,
                1,
                8,
                maximumContextUnits,
                new BigDecimal("0.5"),
                overlap.name(),
                "NO_EVIDENCE",
                digest,
                "maintainer",
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private RetentionPolicy retention(boolean retainPayloads, boolean maskSensitiveFields) {
        return new RetentionPolicy(
                workspaceId,
                tenantId,
                90,
                365,
                retainPayloads,
                maskSensitiveFields,
                2,
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static ExactRetrievalCandidate candidate(
            int rank, UUID documentId, int start, int end, String content) {
        return new ExactRetrievalCandidate(
                rank,
                new BigDecimal("0.9"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                documentId,
                UUID.randomUUID(),
                "sha256:" + "c".repeat(64),
                content,
                "Employee handbook",
                "MARKDOWN",
                start,
                end,
                1,
                "Travel policy",
                2,
                3,
                4);
    }

    private static KnowledgeRetrievalHit publicHit(String content) {
        return new KnowledgeRetrievalHit(
                1,
                BigDecimal.ONE,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "sha256:" + "d".repeat(64),
                content,
                "Title",
                io.apvero.platform.knowledge.KnowledgeSource.Type.TEXT,
                null,
                null,
                null,
                null,
                null);
    }

    private static final class Utf8Estimator implements EmbeddingInputUnitEstimator {
        private int calls;

        @Override
        public String algorithmVersion() {
            return DefaultRetrievalPolicyVersionCatalog
                    .TOKEN_ESTIMATOR_IMPLEMENTATION_VERSION;
        }

        @Override
        public long estimateUnits(String text) {
            calls++;
            return Math.max(1, text.getBytes(StandardCharsets.UTF_8).length);
        }
    }
}
