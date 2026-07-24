package io.apvero.platform.capability;

import java.util.Objects;

public record EmbeddingExecutionQuote(
        EmbeddingRouteSnapshot route,
        long estimatedInputUnits,
        long estimatedCostMicros,
        String currency,
        EmbeddingReplayPolicy replayPolicy) {

    public EmbeddingExecutionQuote {
        Objects.requireNonNull(route, "APVERO_EMBEDDING_ROUTE_REQUIRED");
        if (estimatedInputUnits < 1) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_ESTIMATED_UNITS_INVALID");
        }
        if (estimatedCostMicros < 0) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_COST_INVALID");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_CURRENCY_INVALID");
        }
        Objects.requireNonNull(replayPolicy, "APVERO_EMBEDDING_REPLAY_POLICY_REQUIRED");
    }
}
