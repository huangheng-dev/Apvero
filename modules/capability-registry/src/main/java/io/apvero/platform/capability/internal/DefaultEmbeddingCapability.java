package io.apvero.platform.capability.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingExecutionQuote;
import io.apvero.platform.capability.EmbeddingExecutionRequest;
import io.apvero.platform.capability.EmbeddingExecutionResult;
import io.apvero.platform.capability.EmbeddingReplayPolicy;
import io.apvero.platform.capability.EmbeddingRouteCatalog;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.capability.EmbeddingUsageQuality;
import io.apvero.platform.capability.EmbeddingVectorOutput;
import io.apvero.platform.capability.internal.adapters.springai.DeterministicEmbeddingModel;
import io.apvero.platform.capability.internal.adapters.springai.OpenAiCompatibleEmbeddingAdapter;
import io.apvero.platform.capability.internal.adapters.springai.OpenAiCompatibleEmbeddingRequest;
import io.apvero.platform.capability.internal.adapters.springai.OpenAiCompatibleEmbeddingResult;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultEmbeddingCapability implements EmbeddingCapability {
    private final DSLContext sql;
    private final WorkspaceScopeCatalog workspaces;
    private final EmbeddingRouteCatalog routes;
    private final OpenAiCompatibleEmbeddingAdapter openAi;
    private final DeterministicEmbeddingModel deterministic = new DeterministicEmbeddingModel();

    public DefaultEmbeddingCapability(
            DSLContext sql,
            WorkspaceScopeCatalog workspaces,
            EmbeddingRouteCatalog routes,
            OpenAiCompatibleEmbeddingAdapter openAi) {
        this.sql = sql;
        this.workspaces = workspaces;
        this.routes = routes;
        this.openAi = openAi;
    }

    @Override
    @Transactional(readOnly = true)
    public EmbeddingRouteSnapshot resolveEmbeddingRoute(UUID workspaceId, UUID routeId) {
        EmbeddingRouteSnapshot route = routes.findEmbeddingRoute(workspaceId, routeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "APVERO_EMBEDDING_ROUTE_NOT_FOUND"));
        requireAvailable(route);
        return route;
    }

    @Override
    @Transactional(readOnly = true)
    public EmbeddingExecutionQuote quote(
            UUID workspaceId,
            UUID routeId,
            long estimatedInputUnits) {
        EmbeddingRouteSnapshot route = resolveEmbeddingRoute(workspaceId, routeId);
        RouteExecutionRow execution = executionRow(workspaceId, route.id(), route.reference());
        return EmbeddingCostQuoteCalculator.quote(
                route,
                estimatedInputUnits,
                execution.inputCostMicrosPerMillion(),
                replayPolicy(execution.providerType()));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public EmbeddingExecutionResult embed(EmbeddingExecutionRequest request) {
        WorkspaceScope scope = workspaces.require(request.workspaceId());
        RouteExecutionRow execution =
                executionRow(scope.workspaceId(), null, request.exactRouteReference());
        EmbeddingRouteSnapshot route =
                resolveEmbeddingRoute(scope.workspaceId(), execution.routeId());
        if (!route.reference().equals(request.exactRouteReference())
                || request.orderedInputs().size() > route.profile().maximumBatchSize()) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_ROUTE_REFERENCE_MISMATCH");
        }

        AdapterResult adapterResult = "DETERMINISTIC_LOCAL".equals(execution.providerType())
                ? deterministic(request, route, execution)
                : openAi(request, route, execution);
        List<EmbeddingVectorOutput> outputs = new ArrayList<>(adapterResult.vectors().size());
        for (int index = 0; index < adapterResult.vectors().size(); index++) {
            outputs.add(new EmbeddingVectorOutput(
                    request.orderedInputs().get(index).itemId(),
                    request.orderedInputs().get(index).contentDigest(),
                    floats(adapterResult.vectors().get(index))));
        }
        Long actualUnits = adapterResult.actualInputUnits();
        long pricedUnits = actualUnits == null
                ? request.orderedInputs().stream()
                        .mapToLong(input -> Math.max(
                                1L,
                                input.boundedText()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length))
                        .sum()
                : actualUnits;
        EmbeddingExecutionQuote cost = EmbeddingCostQuoteCalculator.quote(
                route,
                pricedUnits,
                execution.inputCostMicrosPerMillion(),
                replayPolicy(execution.providerType()));
        EmbeddingUsageQuality quality = actualUnits == null
                ? EmbeddingUsageQuality.UNAVAILABLE
                : EmbeddingUsageQuality.ACTUAL;
        EmbeddingExecutionResult result = new EmbeddingExecutionResult(
                route.id(),
                route.reference(),
                route.modelId(),
                execution.modelKey(),
                route.profile().dimension(),
                request.executionIdentity(),
                outputs,
                actualUnits,
                quality,
                cost.estimatedCostMicros(),
                cost.currency(),
                adapterResult.providerRequestIdentity(),
                adapterResult.latencyMillis());
        result.validateAgainst(request);
        return result;
    }

    private AdapterResult deterministic(
            EmbeddingExecutionRequest request,
            EmbeddingRouteSnapshot route,
            RouteExecutionRow execution) {
        if (route.profile().dimension() != DeterministicEmbeddingModel.DIMENSION) {
            throw new IllegalStateException("APVERO_EMBEDDING_DIMENSION_MISMATCH");
        }
        long started = System.nanoTime();
        EmbeddingResponse response = deterministic.call(new EmbeddingRequest(
                request.orderedInputs().stream().map(input -> input.boundedText()).toList(),
                null));
        List<float[]> vectors = response.getResults().stream()
                .map(embedding -> embedding.getOutput().clone())
                .toList();
        return new AdapterResult(vectors, null, null, elapsedMillis(started));
    }

    private AdapterResult openAi(
            EmbeddingExecutionRequest request,
            EmbeddingRouteSnapshot route,
            RouteExecutionRow execution) {
        if (execution.secretReferenceId() == null) {
            throw new IllegalStateException("APVERO_EMBEDDING_SECRET_UNAVAILABLE");
        }
        OpenAiCompatibleEmbeddingResult result = openAi.execute(
                new OpenAiCompatibleEmbeddingRequest(
                        request.workspaceId(),
                        execution.secretReferenceId(),
                        execution.baseUrl(),
                        execution.modelKey(),
                        route.profile().dimension(),
                        route.timeoutMs(),
                        request.orderedInputs().stream()
                                .map(input -> input.boundedText())
                                .toList()));
        return new AdapterResult(
                result.orderedVectors(),
                result.actualInputUnits(),
                result.providerRequestIdentity(),
                result.latencyMillis());
    }

    private RouteExecutionRow executionRow(
            UUID workspaceId,
            UUID routeId,
            String routeReference) {
        WorkspaceScope scope = workspaces.require(workspaceId);
        String name = routeReference.substring(0, routeReference.lastIndexOf('@'));
        long version;
        try {
            version = Long.parseLong(routeReference.substring(routeReference.lastIndexOf('@') + 1));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "APVERO_EMBEDDING_ROUTE_REFERENCE_INVALID", exception);
        }
        var condition = field("route.tenant_id", UUID.class).eq(scope.tenantId())
                .and(field("route.workspace_id", UUID.class).eq(scope.workspaceId()))
                .and(field("route.name", String.class).eq(name))
                .and(field("route.version", Long.class).eq(version));
        if (routeId != null) {
            condition = condition.and(field("route.id", UUID.class).eq(routeId));
        }
        return sql.select(
                        field("route.id", UUID.class),
                        field("model.model_key", String.class),
                        field("model.input_cost_micros_per_million", Long.class),
                        field("provider.provider_type", String.class),
                        field("provider.base_url", String.class),
                        field("provider.secret_reference_id", UUID.class))
                .from(table("model_route").as("route"))
                .join(table("model_definition").as("model"))
                .on(field("model.id", UUID.class).eq(field("route.model_id", UUID.class))
                        .and(field("model.tenant_id", UUID.class)
                                .eq(field("route.tenant_id", UUID.class)))
                        .and(field("model.workspace_id", UUID.class)
                                .eq(field("route.workspace_id", UUID.class))))
                .join(table("model_provider").as("provider"))
                .on(field("provider.id", UUID.class).eq(field("model.provider_id", UUID.class))
                        .and(field("provider.tenant_id", UUID.class)
                                .eq(field("route.tenant_id", UUID.class)))
                        .and(field("provider.workspace_id", UUID.class)
                                .eq(field("route.workspace_id", UUID.class))))
                .where(condition)
                .fetchOptional(record -> new RouteExecutionRow(
                        record.value1(),
                        record.value2(),
                        record.value3() == null ? 0L : record.value3(),
                        record.value4(),
                        record.value5(),
                        record.value6()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "APVERO_EMBEDDING_ROUTE_NOT_FOUND"));
    }

    private static void requireAvailable(EmbeddingRouteSnapshot route) {
        if (!route.availableForNewBuilds()) {
            throw new IllegalStateException("APVERO_EMBEDDING_ROUTE_UNAVAILABLE");
        }
    }

    private static EmbeddingReplayPolicy replayPolicy(String providerType) {
        return "DETERMINISTIC_LOCAL".equals(providerType)
                ? EmbeddingReplayPolicy.SAFE_REPLAY
                : EmbeddingReplayPolicy.RECONCILIATION_REQUIRED;
    }

    private static List<Float> floats(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private record RouteExecutionRow(
            UUID routeId,
            String modelKey,
            long inputCostMicrosPerMillion,
            String providerType,
            String baseUrl,
            UUID secretReferenceId) {}

    private record AdapterResult(
            List<float[]> vectors,
            Long actualInputUnits,
            String providerRequestIdentity,
            long latencyMillis) {
        AdapterResult {
            vectors = vectors.stream().map(float[]::clone).toList();
        }
    }
}
