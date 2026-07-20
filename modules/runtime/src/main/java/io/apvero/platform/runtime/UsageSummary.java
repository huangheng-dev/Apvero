package io.apvero.platform.runtime;

public record UsageSummary(
        long runs,
        long successfulRuns,
        long failedRuns,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long costMicros,
        double averageLatencyMs) {}
