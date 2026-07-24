package io.apvero.platform.capability.internal;

import io.apvero.platform.capability.EmbeddingExecutionQuote;
import io.apvero.platform.capability.EmbeddingReplayPolicy;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import java.math.BigInteger;
import java.util.Objects;

final class EmbeddingCostQuoteCalculator {
    private static final BigInteger UNITS_PER_MILLION = BigInteger.valueOf(1_000_000L);
    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    private EmbeddingCostQuoteCalculator() {}

    static EmbeddingExecutionQuote quote(
            EmbeddingRouteSnapshot route,
            long estimatedInputUnits,
            long inputCostMicrosPerMillion,
            EmbeddingReplayPolicy replayPolicy) {
        Objects.requireNonNull(route, "APVERO_EMBEDDING_ROUTE_REQUIRED");
        Objects.requireNonNull(replayPolicy, "APVERO_EMBEDDING_REPLAY_POLICY_REQUIRED");
        if (estimatedInputUnits < 1) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_ESTIMATED_UNITS_INVALID");
        }
        if (inputCostMicrosPerMillion < 0) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_INPUT_COST_INVALID");
        }

        BigInteger numerator = BigInteger.valueOf(estimatedInputUnits)
                .multiply(BigInteger.valueOf(inputCostMicrosPerMillion));
        BigInteger[] division = numerator.divideAndRemainder(UNITS_PER_MILLION);
        BigInteger roundedCost = division[1].signum() == 0
                ? division[0]
                : division[0].add(BigInteger.ONE);
        if (roundedCost.compareTo(MAX_LONG) > 0) {
            throw new ArithmeticException("APVERO_EMBEDDING_COST_OVERFLOW");
        }
        return new EmbeddingExecutionQuote(
                route, estimatedInputUnits, roundedCost.longValueExact(), "USD", replayPolicy);
    }
}
