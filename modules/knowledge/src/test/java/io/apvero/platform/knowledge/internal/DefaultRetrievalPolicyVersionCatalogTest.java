package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.capability.EmbeddingInputUnitEstimator;
import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.governance.RetentionPolicy;
import io.apvero.platform.governance.RetentionPolicyCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.CreateRetrievalPolicyVersionCommand;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.RetrievalPolicyOverlapHandling;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultRetrievalPolicyVersionCatalogTest {
    private final KnowledgeAvailability availability = mock(KnowledgeAvailability.class);
    private final WorkspaceScopeCatalog workspaces = mock(WorkspaceScopeCatalog.class);
    private final KnowledgeIndexPersistenceRepository policies =
            mock(KnowledgeIndexPersistenceRepository.class);
    private final RetentionPolicyCatalog retention = mock(RetentionPolicyCatalog.class);
    private final EmbeddingInputUnitEstimator estimator = mock(EmbeddingInputUnitEstimator.class);
    private final AuditEventCatalog audit = mock(AuditEventCatalog.class);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final WorkspaceScope scope = new WorkspaceScope(tenantId, workspaceId);
    private final DefaultRetrievalPolicyVersionCatalog catalog =
            new DefaultRetrievalPolicyVersionCatalog(
                    availability, workspaces, policies, retention, estimator, audit);

    @BeforeEach
    void setUp() {
        when(workspaces.require(workspaceId)).thenReturn(scope);
        when(estimator.algorithmVersion())
                .thenReturn(DefaultRetrievalPolicyVersionCatalog.TOKEN_ESTIMATOR_IMPLEMENTATION_VERSION);
        when(retention.getOrCreate(workspaceId)).thenReturn(new RetentionPolicy(
                workspaceId,
                tenantId,
                90,
                365,
                true,
                true,
                1,
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC)));
        when(policies.findPolicyBySlugAndVersion(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(policies.findPolicyByDigest(any(), any())).thenReturn(Optional.empty());
        when(policies.insertPolicyIfAbsent(any(), any())).thenReturn(true);
    }

    @Test
    void publishesCanonicalScopedPolicyAndAuditsOnce() {
        var result = catalog.publish(
                workspaceId,
                command("support", "1.0.0", 8, 4096, "0.1234567",
                        RetrievalPolicyOverlapHandling.COLLAPSE_ADJACENT),
                new KnowledgeCommandContext(" maintainer ", " 127.0.0.1 ", " trace-1 "));

        ArgumentCaptor<RetrievalPolicyRow> row = ArgumentCaptor.forClass(RetrievalPolicyRow.class);
        verify(policies).insertPolicyIfAbsent(eq(scope), row.capture());
        assertThat(row.getValue().minimumScore()).isEqualByComparingTo("0.123457");
        assertThat(row.getValue().retrievalAlgorithmVersion()).isEqualTo("exact-cosine@1.0.0");
        assertThat(row.getValue().tokenEstimatorVersion()).isEqualTo("apvero-utf8-byte@1.0.0");
        assertThat(row.getValue().retentionPolicyVersionAtPublish()).isEqualTo(1);
        assertThat(row.getValue().policyDigest()).matches("^sha256:[a-f0-9]{64}$");
        assertThat(result.reference()).isEqualTo("support@1.0.0");
        assertThat(result.minimumScore()).isEqualByComparingTo("0.123457");
        verify(audit).appendWithDigest(
                workspaceId,
                "maintainer",
                "knowledge.retrieval-policy.published",
                "retrieval-policy-version",
                result.id().toString(),
                "SUCCEEDED",
                "127.0.0.1",
                "trace-1",
                row.getValue().policyDigest());
    }

    @Test
    void equalVersionAndDigestIsIdempotentWithoutAnotherInsertOrAudit() {
        RetrievalPolicyRow existing = row(
                "support",
                "1.0.0",
                8,
                4096,
                new BigDecimal("0.7"),
                RetrievalPolicyOverlapHandling.KEEP,
                1);
        when(policies.findPolicyBySlugAndVersion(scope, "support", "1.0.0"))
                .thenReturn(Optional.of(existing));

        var result = catalog.publish(
                workspaceId,
                command("support", "1.0.0", 8, 4096, "0.7",
                        RetrievalPolicyOverlapHandling.KEEP),
                null);

        assertThat(result.id()).isEqualTo(existing.id());
        verify(policies, never()).insertPolicyIfAbsent(any(), any());
        verify(retention, never()).getOrCreate(any());
        verify(audit, never()).appendWithDigest(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replayKeepsPublishedRetentionProvenanceAfterCurrentPolicyChanges() {
        RetrievalPolicyRow existing = row(
                "support",
                "1.0.0",
                8,
                4096,
                new BigDecimal("0.7"),
                RetrievalPolicyOverlapHandling.KEEP,
                1);
        when(policies.findPolicyBySlugAndVersion(scope, "support", "1.0.0"))
                .thenReturn(Optional.of(existing));
        when(retention.getOrCreate(workspaceId)).thenReturn(new RetentionPolicy(
                workspaceId,
                tenantId,
                30,
                730,
                false,
                true,
                2,
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC)));

        var result = catalog.publish(
                workspaceId,
                command("support", "1.0.0", 8, 4096, "0.7",
                        RetrievalPolicyOverlapHandling.KEEP),
                null);

        assertThat(result.retentionPolicyVersionAtPublish()).isEqualTo(1);
        verify(retention, never()).getOrCreate(any());
    }

    @Test
    void reusedVersionWithDifferentBehaviorFailsClosed() {
        RetrievalPolicyRow existing = row(
                "support",
                "1.0.0",
                8,
                4096,
                new BigDecimal("0.7"),
                RetrievalPolicyOverlapHandling.KEEP,
                1);
        when(policies.findPolicyBySlugAndVersion(scope, "support", "1.0.0"))
                .thenReturn(Optional.of(existing));

        assertCode(
                () -> catalog.publish(
                        workspaceId,
                        command("support", "1.0.0", 9, 4096, "0.7",
                                RetrievalPolicyOverlapHandling.KEEP),
                        null),
                "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_VERSION_CONFLICT");
    }

    @Test
    void duplicateBehaviorUnderAnotherIdentityFailsClosed() {
        when(policies.findPolicyByDigest(eq(scope), any()))
                .thenReturn(Optional.of(row(
                        "existing", "1.0.0", 8, 4096, new BigDecimal("0.7"),
                        RetrievalPolicyOverlapHandling.KEEP, 1)));

        assertCode(
                () -> catalog.publish(
                        workspaceId,
                        command("new-name", "2.0.0", 8, 4096, "0.7",
                                RetrievalPolicyOverlapHandling.KEEP),
                        null),
                "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_DUPLICATE");
    }

    @Test
    void concurrentWinnerFoundByDigestConvergesOnTheSameIdentity() {
        RetrievalPolicyRow winner = row(
                "support",
                "1.0.0",
                8,
                4096,
                new BigDecimal("0.7"),
                RetrievalPolicyOverlapHandling.KEEP,
                1);
        when(policies.findPolicyByDigest(eq(scope), any())).thenReturn(Optional.of(winner));

        var result = catalog.publish(
                workspaceId,
                command("support", "1.0.0", 8, 4096, "0.7",
                        RetrievalPolicyOverlapHandling.KEEP),
                null);

        assertThat(result.id()).isEqualTo(winner.id());
        verify(policies, never()).insertPolicyIfAbsent(any(), any());
        verify(audit, never()).appendWithDigest(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void validatesPublicRangesBeforeMaterializingRetention() {
        assertCode(
                () -> catalog.publish(
                        workspaceId,
                        command("support", "1.0.0", 1, 127, "0",
                                RetrievalPolicyOverlapHandling.KEEP),
                        null),
                "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_REQUEST_INVALID");
        verify(retention, never()).getOrCreate(any());
    }

    @Test
    void rejectsUnexpectedEstimatorAndRetentionScope() {
        when(estimator.algorithmVersion()).thenReturn("unexpected");
        assertCode(
                () -> catalog.publish(
                        workspaceId,
                        command("support", "1.0.0", 8, 4096, "0.7",
                                RetrievalPolicyOverlapHandling.KEEP),
                        null),
                "APVERO_KNOWLEDGE_TOKEN_ESTIMATOR_UNSUPPORTED");

        when(estimator.algorithmVersion())
                .thenReturn(DefaultRetrievalPolicyVersionCatalog.TOKEN_ESTIMATOR_IMPLEMENTATION_VERSION);
        when(retention.getOrCreate(workspaceId)).thenReturn(new RetentionPolicy(
                UUID.randomUUID(),
                tenantId,
                90,
                365,
                true,
                true,
                1,
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC)));
        assertCode(
                () -> catalog.publish(
                        workspaceId,
                        command("support", "1.0.0", 8, 4096, "0.7",
                                RetrievalPolicyOverlapHandling.KEEP),
                        null),
                "APVERO_KNOWLEDGE_RETENTION_POLICY_INVALID");
    }

    private RetrievalPolicyRow row(
            String slug,
            String version,
            int topK,
            int contextTokens,
            BigDecimal score,
            RetrievalPolicyOverlapHandling overlap,
            long retentionVersion) {
        String digest = RetrievalPolicyDigests.canonical(
                DefaultRetrievalPolicyVersionCatalog.RETRIEVAL_ALGORITHM_VERSION,
                DefaultRetrievalPolicyVersionCatalog.TOKEN_ESTIMATOR_VERSION,
                retentionVersion,
                topK,
                contextTokens,
                score,
                overlap.name(),
                "NO_EVIDENCE");
        return new RetrievalPolicyRow(
                UUID.randomUUID(),
                tenantId,
                workspaceId,
                slug,
                version,
                DefaultRetrievalPolicyVersionCatalog.RETRIEVAL_ALGORITHM_VERSION,
                DefaultRetrievalPolicyVersionCatalog.TOKEN_ESTIMATOR_VERSION,
                retentionVersion,
                topK,
                contextTokens,
                score,
                overlap.name(),
                "NO_EVIDENCE",
                digest,
                "maintainer",
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static CreateRetrievalPolicyVersionCommand command(
            String slug,
            String version,
            int topK,
            int contextTokens,
            String score,
            RetrievalPolicyOverlapHandling overlap) {
        return new CreateRetrievalPolicyVersionCommand(
                slug, version, topK, contextTokens, new BigDecimal(score), overlap);
    }

    private static void assertCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(KnowledgeException.class)
                .hasMessage(code);
    }
}
