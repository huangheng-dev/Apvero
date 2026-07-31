package io.apvero.platform.capability;

public record ExecutionRetentionDecision(
        long version,
        boolean retainPayloads,
        boolean maskSensitiveFields) {
    public ExecutionRetentionDecision {
        if (version < 1) {
            throw new IllegalArgumentException("APVERO_EXECUTION_RETENTION_INVALID");
        }
    }
}
