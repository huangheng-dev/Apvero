package io.apvero.platform.knowledge.internal;

import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.ComponentState.DISPATCHED;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.ComponentState.FAILED;
import static io.apvero.platform.knowledge.internal.KnowledgeEmbeddingRecoveryDecider.ComponentState.RECONCILIATION_REQUIRED;
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
        assertThat(decide(FAILED, COMPLETE_EQUAL, EmbeddingReplayPolicy.SAFE_REPLAY))
                .isEqualTo(LEDGER_ARTIFACT_INCONSISTENCY);
        assertThat(decide(RECONCILIATION_REQUIRED, COMPLETE_EQUAL, EmbeddingReplayPolicy.SAFE_REPLAY))
                .isEqualTo(LEDGER_ARTIFACT_INCONSISTENCY);
    }

    @Test
    void exhaustsEveryComponentEntryAndReplayPolicyCombination() {
        for (KnowledgeEmbeddingRecoveryDecider.ComponentState component
                : KnowledgeEmbeddingRecoveryDecider.ComponentState.values()) {
            for (KnowledgeEmbeddingRecoveryDecider.EntryState entries
                    : KnowledgeEmbeddingRecoveryDecider.EntryState.values()) {
                for (EmbeddingReplayPolicy replayPolicy : EmbeddingReplayPolicy.values()) {
                    assertThat(decide(component, entries, replayPolicy))
                            .as("%s / %s / %s", component, entries, replayPolicy)
                            .isEqualTo(expected(component, entries, replayPolicy));
                }
            }
        }
    }

    private static KnowledgeEmbeddingRecoveryDecider.RecoveryAction expected(
            KnowledgeEmbeddingRecoveryDecider.ComponentState component,
            KnowledgeEmbeddingRecoveryDecider.EntryState entries,
            EmbeddingReplayPolicy replayPolicy) {
        if (entries == PARTIAL
                || (entries == COMPLETE_DIFFERENT && !component.terminal())) {
            return INTEGRITY_FAILURE;
        }
        if (component.terminal() && entries != COMPLETE_EQUAL) {
            return LEDGER_ARTIFACT_INCONSISTENCY;
        }
        if (component == SUCCEEDED) {
            return COMPLETE;
        }
        if (component == FAILED || component == RECONCILIATION_REQUIRED) {
            return LEDGER_ARTIFACT_INCONSISTENCY;
        }
        if (entries == COMPLETE_EQUAL) {
            return SETTLE_ONLY;
        }
        return switch (component) {
            case NONE -> ADMIT;
            case RESERVED -> DISPATCH;
            case DISPATCHED -> replayPolicy == EmbeddingReplayPolicy.SAFE_REPLAY
                    ? REPLAY
                    : RECONCILE;
            case SUCCEEDED, FAILED, RECONCILIATION_REQUIRED ->
                    LEDGER_ARTIFACT_INCONSISTENCY;
        };
    }

    private static KnowledgeEmbeddingRecoveryDecider.RecoveryAction decide(
            KnowledgeEmbeddingRecoveryDecider.ComponentState component,
            KnowledgeEmbeddingRecoveryDecider.EntryState entries,
            EmbeddingReplayPolicy replayPolicy) {
        return KnowledgeEmbeddingRecoveryDecider.decide(component, entries, replayPolicy);
    }
}
