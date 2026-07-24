package io.apvero.platform.capability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.capability.EmbeddingExecutionQuote;
import io.apvero.platform.capability.EmbeddingNormalization;
import io.apvero.platform.capability.EmbeddingReplayPolicy;
import io.apvero.platform.capability.EmbeddingRouteProfile;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.capability.ModelRouteCapability;
import io.apvero.platform.capability.ModelRouteStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmbeddingCostQuoteCalculatorTest {

    @Test
    void roundsUpWithoutLosingTheExactPinnedRoute() {
        EmbeddingRouteSnapshot route = route();

        EmbeddingExecutionQuote smallest = EmbeddingCostQuoteCalculator.quote(
                route, 1, 1, EmbeddingReplayPolicy.RECONCILIATION_REQUIRED);
        EmbeddingExecutionQuote exactMillion = EmbeddingCostQuoteCalculator.quote(
                route, 1_000_000, 37, EmbeddingReplayPolicy.SAFE_REPLAY);
        EmbeddingExecutionQuote rounded = EmbeddingCostQuoteCalculator.quote(
                route, 1_000_001, 37, EmbeddingReplayPolicy.SAFE_REPLAY);

        assertThat(smallest.estimatedCostMicros()).isEqualTo(1);
        assertThat(exactMillion.estimatedCostMicros()).isEqualTo(37);
        assertThat(rounded.estimatedCostMicros()).isEqualTo(38);
        assertThat(rounded.route()).isSameAs(route);
        assertThat(rounded.currency()).isEqualTo("USD");
        assertThat(rounded.replayPolicy()).isEqualTo(EmbeddingReplayPolicy.SAFE_REPLAY);
    }

    @Test
    void rejectsInvalidAndOverflowingCostsWithStableCodes() {
        assertThatThrownBy(() -> EmbeddingCostQuoteCalculator.quote(
                route(), 1, -1, EmbeddingReplayPolicy.RECONCILIATION_REQUIRED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EMBEDDING_INPUT_COST_INVALID");
        assertThatThrownBy(() -> EmbeddingCostQuoteCalculator.quote(
                route(), Long.MAX_VALUE, Long.MAX_VALUE, EmbeddingReplayPolicy.RECONCILIATION_REQUIRED))
                .isInstanceOf(ArithmeticException.class)
                .hasMessage("APVERO_EMBEDDING_COST_OVERFLOW");
    }

    private static EmbeddingRouteSnapshot route() {
        return new EmbeddingRouteSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "embedding", 7,
                UUID.randomUUID(), ModelRouteCapability.EMBEDDING, ModelRouteStatus.PUBLISHED,
                30_000, new EmbeddingRouteProfile(256, 8_192, 64, EmbeddingNormalization.L2),
                true, "READY", OffsetDateTime.now(ZoneOffset.UTC));
    }
}
