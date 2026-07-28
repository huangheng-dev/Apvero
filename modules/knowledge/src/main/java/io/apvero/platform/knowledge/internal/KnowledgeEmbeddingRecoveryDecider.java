package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingReplayPolicy;
import java.util.Objects;

final class KnowledgeEmbeddingRecoveryDecider {
    private KnowledgeEmbeddingRecoveryDecider() {}

    static RecoveryAction decide(
            ComponentState component,
            EntryState entries,
            EmbeddingReplayPolicy replayPolicy) {
        Objects.requireNonNull(component, "APVERO_EXECUTION_COMPONENT_STATE_REQUIRED");
        Objects.requireNonNull(entries, "APVERO_KNOWLEDGE_ENTRY_STATE_REQUIRED");
        Objects.requireNonNull(replayPolicy, "APVERO_EMBEDDING_REPLAY_POLICY_REQUIRED");

        if (entries == EntryState.PARTIAL
                || (entries == EntryState.COMPLETE_DIFFERENT && !component.terminal())) {
            return RecoveryAction.INTEGRITY_FAILURE;
        }
        if (component.terminal() && entries != EntryState.COMPLETE_EQUAL) {
            return RecoveryAction.LEDGER_ARTIFACT_INCONSISTENCY;
        }
        if (component == ComponentState.SUCCEEDED) {
            return RecoveryAction.COMPLETE;
        }
        if ((component == ComponentState.FAILED
                || component == ComponentState.RECONCILIATION_REQUIRED)
                && entries == EntryState.COMPLETE_EQUAL) {
            return RecoveryAction.LEDGER_ARTIFACT_INCONSISTENCY;
        }
        if (entries == EntryState.COMPLETE_EQUAL) {
            return RecoveryAction.SETTLE_ONLY;
        }
        return switch (component) {
            case NONE -> RecoveryAction.ADMIT;
            case RESERVED -> RecoveryAction.DISPATCH;
            case DISPATCHED -> replayPolicy == EmbeddingReplayPolicy.SAFE_REPLAY
                    ? RecoveryAction.REPLAY
                    : RecoveryAction.RECONCILE;
            case SUCCEEDED, FAILED, RECONCILIATION_REQUIRED ->
                    RecoveryAction.LEDGER_ARTIFACT_INCONSISTENCY;
        };
    }

    enum ComponentState {
        NONE,
        RESERVED,
        DISPATCHED,
        SUCCEEDED,
        FAILED,
        RECONCILIATION_REQUIRED;

        boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == RECONCILIATION_REQUIRED;
        }
    }

    enum EntryState {
        NONE,
        COMPLETE_EQUAL,
        COMPLETE_DIFFERENT,
        PARTIAL
    }

    enum RecoveryAction {
        ADMIT,
        DISPATCH,
        REPLAY,
        RECONCILE,
        SETTLE_ONLY,
        COMPLETE,
        INTEGRITY_FAILURE,
        LEDGER_ARTIFACT_INCONSISTENCY
    }
}
