package io.apvero.platform.capability.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import io.apvero.platform.capability.ExecutionCapabilityPolicy;
import io.apvero.platform.capability.ExecutionPermit;
import io.apvero.platform.capability.ModelRouteReadiness;
import io.apvero.platform.capability.ModelRouteReadinessCatalog;
import io.apvero.platform.governance.ExecutionAdmission;
import io.apvero.platform.governance.ExecutionGovernance;
import io.apvero.platform.governance.SecretReferenceCatalog;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class DefaultExecutionCapabilityPolicy implements ExecutionCapabilityPolicy, ModelRouteReadinessCatalog {
    private final DSLContext sql;
    private final ExecutionGovernance governance;
    private final SecretReferenceCatalog secrets;
    private final WorkspaceScopeCatalog workspaces;

    public DefaultExecutionCapabilityPolicy(DSLContext sql, ExecutionGovernance governance,
            SecretReferenceCatalog secrets, WorkspaceScopeCatalog workspaces) {
        this.sql = sql;
        this.governance = governance;
        this.secrets = secrets;
        this.workspaces = workspaces;
    }

    @Override
    public ExecutionPermit admit(UUID workspaceId, UUID applicationId, String modelRouteReference,
            String actorId, String traceId, JsonNode input) {
        RouteCost route = routeCost(workspaceId, modelRouteReference);
        long estimatedInputTokens = Math.max(1, input.toString().length() / 4L);
        long inputCost = multiplyAndRoundUp(estimatedInputTokens, route.inputCostMicrosPerMillion());
        long outputCost = multiplyAndRoundUp(route.maxOutputTokens(), route.outputCostMicrosPerMillion());
        ExecutionAdmission admission = governance.admit(workspaceId, applicationId, route.id(), actorId,
                traceId, Math.addExact(inputCost, outputCost));
        return new ExecutionPermit(admission.reservationId(), route.id(), admission.retainPayloads(),
                admission.maskSensitiveFields());
    }

    @Override
    public void settle(UUID reservationId, long actualCostMicros, boolean succeeded) {
        governance.settle(reservationId, actualCostMicros, succeeded);
    }

    @Override
    public List<ModelRouteReadiness> list(UUID workspaceId) {
        workspaces.require(workspaceId);
        return sql.select(
                        field("model_route.id", UUID.class).as("route_id"),
                        field("model_route.name", String.class).as("route_name"),
                        field("model_route.version", Long.class).as("route_version"),
                        field("model_route.status", String.class).as("route_status"),
                        field("model_definition.enabled", Boolean.class).as("model_enabled"),
                        field("model_provider.name", String.class).as("provider_name"),
                        field("model_provider.provider_type", String.class).as("provider_type"),
                        field("model_provider.enabled", Boolean.class).as("provider_enabled"),
                        field("model_provider.secret_reference_id", UUID.class).as("secret_id"))
                .from(table("model_route"))
                .join(table("model_definition"))
                .on(field("model_route.model_id", UUID.class).eq(field("model_definition.id", UUID.class)))
                .join(table("model_provider"))
                .on(field("model_definition.provider_id", UUID.class).eq(field("model_provider.id", UUID.class)))
                .where(field("model_route.workspace_id", UUID.class).eq(workspaceId))
                .orderBy(field("model_route.created_at").desc())
                .fetch(record -> readiness(workspaceId, record));
    }

    private ModelRouteReadiness readiness(UUID workspaceId, Record record) {
        boolean published = "PUBLISHED".equals(record.get("route_status", String.class));
        boolean modelEnabled = Boolean.TRUE.equals(record.get("model_enabled", Boolean.class));
        boolean providerEnabled = Boolean.TRUE.equals(record.get("provider_enabled", Boolean.class));
        UUID secretId = record.get("secret_id", UUID.class);
        boolean secretReady = secretId == null || secrets.isAvailable(workspaceId, secretId);
        boolean ready = published && modelEnabled && providerEnabled && secretReady;
        String reason = !published ? "ROUTE_NOT_PUBLISHED"
                : !modelEnabled ? "MODEL_DISABLED"
                : !providerEnabled ? "PROVIDER_DISABLED"
                : !secretReady ? "SECRET_UNAVAILABLE" : "READY";
        return new ModelRouteReadiness(record.get("route_id", UUID.class),
                record.get("route_name", String.class) + "@" + record.get("route_version", Long.class),
                record.get("provider_name", String.class), record.get("provider_type", String.class),
                record.get("route_status", String.class), ready, reason);
    }

    private RouteCost routeCost(UUID workspaceId, String reference) {
        if (reference == null || !reference.contains("@")) throw new IllegalArgumentException("Pinned model route is invalid.");
        int marker = reference.lastIndexOf('@');
        String name = reference.substring(0, marker);
        long version;
        try {
            version = Long.parseLong(reference.substring(marker + 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Pinned model route version is invalid.");
        }
        return sql.select(field("model_route.id", UUID.class).as("route_id"),
                        field("model_route.max_output_tokens", Integer.class).as("max_output"),
                        field("model_definition.input_cost_micros_per_million", Long.class).as("input_cost"),
                        field("model_definition.output_cost_micros_per_million", Long.class).as("output_cost"))
                .from(table("model_route"))
                .join(table("model_definition"))
                .on(field("model_route.model_id", UUID.class).eq(field("model_definition.id", UUID.class)))
                .where(field("model_route.workspace_id", UUID.class).eq(workspaceId)
                        .and(field("model_route.name", String.class).eq(name))
                        .and(field("model_route.version", Long.class).eq(version)))
                .fetchOptional(record -> new RouteCost(record.get("route_id", UUID.class),
                        record.get("max_output", Integer.class), record.get("input_cost", Long.class),
                        record.get("output_cost", Long.class)))
                .orElseThrow(() -> new IllegalArgumentException("Pinned model route does not exist."));
    }

    private long multiplyAndRoundUp(long tokens, long microsPerMillion) {
        if (tokens == 0 || microsPerMillion == 0) return 0;
        return Math.addExact(Math.multiplyExact(tokens, microsPerMillion), 999_999L) / 1_000_000L;
    }

    private record RouteCost(UUID id, int maxOutputTokens, long inputCostMicrosPerMillion,
            long outputCostMicrosPerMillion) {}
}
