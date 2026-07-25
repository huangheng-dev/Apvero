package io.apvero.platform.knowledge.internal;

import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.ComponentState.DISPATCHED;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.ComponentState.RESERVED;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.ComponentState.SUCCEEDED;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.EntryState.COMPLETE_DIFFERENT;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.EntryState.COMPLETE_EQUAL;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.EntryState.NONE;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.EntryState.PARTIAL;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction.ADMIT;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction.COMPLETE;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction.DISPATCH;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction.INTEGRITY_FAILURE;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction.LEDGER_ARTIFACT_INCONSISTENCY;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction.RECONCILE;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction.REPLAY;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.RecoveryAction.SETTLE_ONLY;
import static org.assertj.core.api.Assertions.assertThat;

import io.apvero.platform.capability.EmbeddingReplayPolicy;
import org.junit.jupiter.api.Test;

class KnowledgeEmbeddingRecoveryDeciderTest {
    @Test
    void coversEveryApprovedCrashBoundary() {
        assertThat(decide(
                        KnowledgeEmbeddingRecoveryDecider.ComponentState.NONE,
                        NONE,
                        EmbeddingReplayPolicy.RECONCILIATION_REQUIRED))
                .isEqualTo(ADMIT);
        assertThat(decide(RESERVED, NONE, EmbeddingReplayPolicy.RECONCILIATION_REQUIRED))
                .isEqualTo(DISPATCH);
        assertThat(decide(DISPATCHED, NONE, EmbeddingReplayPolicy.SAFE_REPLAY))
                .isEqualTo(REPLAY);
        assertThat(decide(DISPATCHED, NONE, EmbeddingReplayPolicy.RECONCILIATION_REQUIRED))
                .isEqualTo(RECONCILE);
        assertThat(decide(DISPATCHED, COMPLETE_EQUAL, EmbeddingReplayPolicy.RECONCILIATION_REQUIRED))
                .isEqualTo(SETTLE_ONLY);
        assertThat(decide(DISPATCHED, PARTIAL, EmbeddingReplayPolicy.SAFE_REPLAY))
                .isEqualTo(INTEGRITY_FAILURE);
        assertThat(decide(SUCCEEDED, COMPLETE_EQUAL, EmbeddingReplayPolicy.RECONCILIATION_REQUIRED))
                .isEqualTo(COMPLETE);
        assertThat(decide(SUCCEEDED, COMPLETE_DIFFERENT, EmbeddingReplayPolicy.RECONCILIATION_REQUIRED))
                .isEqualTo(LEDGER_ARTIFACT_INCONSISTENCY);
    }

    private static KnowledgeEmbeddingRecoveryDecider.RecoveryAction decide(
            KnowledgeEmbeddingRecoveryDecider.ComponentState component,
            KnowledgeEmbeddingRecoveryDecider.EntryState entries,
            EmbeddingReplayPolicy replayPolicy) {
        return KnowledgeEmbeddingRecoveryDecider.decide(component, entries, replayPolicy);
    }
}
