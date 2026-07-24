package io.apvero.platform.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmbeddingContractTest {
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    @Test
    void routeProfileMatchesTheApprovedStorageEnvelope() {
        EmbeddingRouteProfile profile = new EmbeddingRouteProfile(
                256, 8_192, 64, EmbeddingNormalization.L2);
        EmbeddingRouteSnapshot route = new EmbeddingRouteSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "quick-start-embedding", 3,
                UUID.randomUUID(), ModelRouteCapability.EMBEDDING, ModelRouteStatus.PUBLISHED,
                30_000, profile, true, "READY",
                OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(route.reference()).isEqualTo("quick-start-embedding@3");
        assertThat(route.availableForNewBuilds()).isTrue();
        assertThat(EmbeddingRouteProfile.MAXIMUM_VECTOR_DIMENSION).isEqualTo(16_000);

        assertThatThrownBy(() -> new EmbeddingRouteProfile(
                16_001, 8_192, 64, EmbeddingNormalization.L2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EMBEDDING_DIMENSION_INVALID");
        assertThatThrownBy(() -> new EmbeddingRouteSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "invalid", 1,
                UUID.randomUUID(), ModelRouteCapability.CHAT, ModelRouteStatus.PUBLISHED,
                30_000, profile, true, "READY", OffsetDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EMBEDDING_ROUTE_CAPABILITY_INVALID");
    }

    @Test
    void requestAndResultPreserveExactOrderAndDefensiveCopies() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        ArrayList<EmbeddingInput> mutableInputs = new ArrayList<>(List.of(
                new EmbeddingInput(firstId, DIGEST_A, "First"),
                new EmbeddingInput(secondId, DIGEST_B, "Second")));
        EmbeddingExecutionRequest request = new EmbeddingExecutionRequest(
                UUID.randomUUID(), "quick-start-embedding@1", "build-1:batch-1", mutableInputs);
        mutableInputs.clear();

        EmbeddingExecutionResult result = result(List.of(
                new EmbeddingVectorOutput(firstId, DIGEST_A, List.of(1.0f, 0.0f)),
                new EmbeddingVectorOutput(secondId, DIGEST_B, List.of(0.0f, 1.0f))));

        result.validateAgainst(request);
        assertThat(request.orderedInputs()).hasSize(2);
        assertThatThrownBy(() -> result.validateAgainst(new EmbeddingExecutionRequest(
                request.workspaceId(), request.exactRouteReference(), request.executionIdentity(),
                List.of(request.orderedInputs().get(1), request.orderedInputs().get(0)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EMBEDDING_OUTPUT_MAPPING_INVALID");
    }

    @Test
    void invalidVectorsAndUsageFailBeforeCrossingTheModuleBoundary() {
        assertThatThrownBy(() -> new EmbeddingVectorOutput(
                UUID.randomUUID(), DIGEST_A, List.of(0.0f, 0.0f)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EMBEDDING_VECTOR_ZERO_NORM");
        assertThatThrownBy(() -> new EmbeddingVectorOutput(
                UUID.randomUUID(), DIGEST_A, List.of(Float.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EMBEDDING_VECTOR_NON_FINITE");
        assertThatThrownBy(() -> new EmbeddingExecutionResult(
                UUID.randomUUID(), "quick-start-embedding@1", UUID.randomUUID(), "local-model", 2,
                "build-1:batch-1",
                List.of(new EmbeddingVectorOutput(UUID.randomUUID(), DIGEST_A, List.of(1.0f, 0.0f))),
                null, EmbeddingUsageQuality.ACTUAL, 0, "USD", null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EMBEDDING_USAGE_INCONSISTENT");
    }

    @Test
    void quotePinsTheExactRouteCostAndReplayDecision() {
        EmbeddingRouteSnapshot route = route();
        EmbeddingExecutionQuote quote = new EmbeddingExecutionQuote(
                route, 1_024, 8, "USD", EmbeddingReplayPolicy.SAFE_REPLAY);

        assertThat(quote.route()).isSameAs(route);
        assertThat(quote.estimatedInputUnits()).isEqualTo(1_024);
        assertThat(quote.estimatedCostMicros()).isEqualTo(8);
        assertThat(quote.currency()).isEqualTo("USD");
        assertThat(quote.replayPolicy()).isEqualTo(EmbeddingReplayPolicy.SAFE_REPLAY);
        assertThat(EmbeddingReplayPolicy.DEFAULT)
                .isEqualTo(EmbeddingReplayPolicy.RECONCILIATION_REQUIRED);

        assertThatThrownBy(() -> new EmbeddingExecutionQuote(
                route, 0, 0, "USD", EmbeddingReplayPolicy.RECONCILIATION_REQUIRED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EMBEDDING_ESTIMATED_UNITS_INVALID");
    }

    private EmbeddingRouteSnapshot route() {
        return new EmbeddingRouteSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "quick-start-embedding", 1,
                UUID.randomUUID(), ModelRouteCapability.EMBEDDING, ModelRouteStatus.PUBLISHED,
                30_000, new EmbeddingRouteProfile(256, 8_192, 64, EmbeddingNormalization.L2),
                true, "READY", OffsetDateTime.now(ZoneOffset.UTC));
    }

    private EmbeddingExecutionResult result(List<EmbeddingVectorOutput> outputs) {
        return new EmbeddingExecutionResult(
                UUID.randomUUID(), "quick-start-embedding@1", UUID.randomUUID(), "local-model", 2,
                "build-1:batch-1", outputs, 11L, EmbeddingUsageQuality.ACTUAL, 0,
                "USD", "safe-request-id", 4);
    }
}
