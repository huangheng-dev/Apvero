package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.apvero.platform.capability.EmbeddingReplayPolicy;
import org.junit.jupiter.api.Test;

class KnowledgeEmbeddingFailureNormalizerTest {
    @Test
    void unsafeTimeoutRequiresReconciliation() {
        KnowledgeIndexBuildFailure failure = KnowledgeEmbeddingFailureNormalizer.normalize(
                new IllegalStateException("APVERO_EMBEDDING_PROVIDER_TIMEOUT"),
                EmbeddingReplayPolicy.RECONCILIATION_REQUIRED);

        assertThat(failure.category())
                .isEqualTo(KnowledgeIndexBuildFailure.Category.AMBIGUOUS);
        assertThat(failure.reconciliationRequired()).isTrue();
        assertThat(failure.retryable()).isFalse();
    }

    @Test
    void safeTimeoutIsRetryableButProviderRejectionIsPermanent() {
        KnowledgeIndexBuildFailure timeout = KnowledgeEmbeddingFailureNormalizer.normalize(
                new IllegalStateException("APVERO_EMBEDDING_PROVIDER_TIMEOUT"),
                EmbeddingReplayPolicy.SAFE_REPLAY);
        KnowledgeIndexBuildFailure rejected = KnowledgeEmbeddingFailureNormalizer.normalize(
                new IllegalStateException("APVERO_EMBEDDING_PROVIDER_REJECTED"),
                EmbeddingReplayPolicy.SAFE_REPLAY);

        assertThat(timeout.category())
                .isEqualTo(KnowledgeIndexBuildFailure.Category.TRANSIENT);
        assertThat(timeout.retryable()).isTrue();
        assertThat(rejected.category())
                .isEqualTo(KnowledgeIndexBuildFailure.Category.PERMANENT);
        assertThat(rejected.retryable()).isFalse();
    }

    @Test
    void invalidOutputAndSecretsUseBoundedStableFailures() {
        KnowledgeIndexBuildFailure invalid = KnowledgeEmbeddingFailureNormalizer.normalize(
                new IllegalStateException("APVERO_EMBEDDING_OUTPUT_MAPPING_INVALID"),
                EmbeddingReplayPolicy.SAFE_REPLAY);
        KnowledgeIndexBuildFailure secret = KnowledgeEmbeddingFailureNormalizer.normalize(
                new IllegalStateException("APVERO_EMBEDDING_SECRET_UNAVAILABLE"),
                EmbeddingReplayPolicy.SAFE_REPLAY);
        KnowledgeIndexBuildFailure unknown = KnowledgeEmbeddingFailureNormalizer.normalize(
                new IllegalStateException("provider body with sensitive detail"),
                EmbeddingReplayPolicy.SAFE_REPLAY);

        assertThat(invalid.category())
                .isEqualTo(KnowledgeIndexBuildFailure.Category.VALIDATION);
        assertThat(secret.category())
                .isEqualTo(KnowledgeIndexBuildFailure.Category.SECURITY);
        assertThat(unknown.code()).isEqualTo("APVERO_EMBEDDING_INTERNAL");
    }
}
