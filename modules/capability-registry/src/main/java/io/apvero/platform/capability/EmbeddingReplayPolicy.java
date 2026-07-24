package io.apvero.platform.capability;

public enum EmbeddingReplayPolicy {
    SAFE_REPLAY,
    RECONCILIATION_REQUIRED;

    public static final EmbeddingReplayPolicy DEFAULT = RECONCILIATION_REQUIRED;
}
