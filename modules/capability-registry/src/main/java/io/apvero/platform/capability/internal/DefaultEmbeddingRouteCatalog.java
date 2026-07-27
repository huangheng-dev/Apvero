package io.apvero.platform.capability.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import io.apvero.platform.capability.EmbeddingNormalization;
import io.apvero.platform.capability.EmbeddingRouteCatalog;
import io.apvero.platform.capability.EmbeddingRouteProfile;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.capability.ModelRouteCapability;
import io.apvero.platform.capability.ModelRouteStatus;
import io.apvero.platform.governance.SecretReferenceCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultEmbeddingRouteCatalog implements EmbeddingRouteCatalog {
    private final DSLContext sql;
    private final WorkspaceScopeCatalog workspaces;
    private final SecretReferenceCatalog secrets;

    public DefaultEmbeddingRouteCatalog(
            DSLContext sql,
            WorkspaceScopeCatalog workspaces,
            SecretReferenceCatalog secrets) {
        this.sql = sql;
        this.workspaces = workspaces;
        this.secrets = secrets;
    }

    @Override
    public Optional<EmbeddingRouteSnapshot> findEmbeddingRoute(UUID workspaceId, UUID routeId) {
        WorkspaceScope scope = workspaces.require(workspaceId);
        return sql.select(
                        field("route.id", UUID.class).as("route_id"),
                        field("route.name", String.class).as("route_name"),
                        field("route.version", Long.class).as("route_version"),
                        field("route.model_id", UUID.class).as("model_id"),
                        field("route.status", String.class).as("route_status"),
                        field("route.timeout_ms", Integer.class).as("timeout_ms"),
                        field("route.embedding_dimension", Integer.class).as("dimension"),
                        field("route.embedding_maximum_input_tokens", Integer.class).as("input_limit"),
                        field("route.embedding_maximum_batch_size", Integer.class).as("batch_limit"),
                        field("route.embedding_normalization", String.class).as("normalization"),
                        field("route.created_at", OffsetDateTime.class).as("created_at"),
                        field("model.enabled", Boolean.class).as("model_enabled"),
                        field("provider.enabled", Boolean.class).as("provider_enabled"),
                        field("provider.secret_reference_id", UUID.class).as("secret_id"))
                .from(table("model_route").as("route"))
                .join(table("model_definition").as("model"))
                .on(field("model.id", UUID.class).eq(field("route.model_id", UUID.class))
                        .and(field("model.tenant_id", UUID.class).eq(field("route.tenant_id", UUID.class)))
                        .and(field("model.workspace_id", UUID.class).eq(field("route.workspace_id", UUID.class))))
                .join(table("model_provider").as("provider"))
                .on(field("provider.id", UUID.class).eq(field("model.provider_id", UUID.class))
                        .and(field("provider.tenant_id", UUID.class).eq(field("model.tenant_id", UUID.class)))
                        .and(field("provider.workspace_id", UUID.class).eq(field("model.workspace_id", UUID.class))))
                .where(field("route.id", UUID.class).eq(routeId)
                        .and(field("route.tenant_id", UUID.class).eq(scope.tenantId()))
                        .and(field("route.workspace_id", UUID.class).eq(scope.workspaceId()))
                        .and(field("route.route_capability", String.class).eq("EMBEDDING")))
                .fetchOptional(record -> map(scope, record));
    }

    private EmbeddingRouteSnapshot map(WorkspaceScope scope, Record record) {
        String routeStatus = record.get("route_status", String.class);
        boolean published = ModelRouteStatus.PUBLISHED.name().equals(routeStatus);
        boolean modelEnabled = Boolean.TRUE.equals(record.get("model_enabled", Boolean.class));
        boolean providerEnabled = Boolean.TRUE.equals(record.get("provider_enabled", Boolean.class));
        UUID secretId = record.get("secret_id", UUID.class);
        boolean secretReady = secretId == null || secrets.isAvailable(scope.workspaceId(), secretId);
        boolean ready = published && modelEnabled && providerEnabled && secretReady;
        String readinessCode = !published ? "ROUTE_NOT_PUBLISHED"
                : !modelEnabled ? "MODEL_DISABLED"
                : !providerEnabled ? "PROVIDER_DISABLED"
                : !secretReady ? "SECRET_UNAVAILABLE"
                : "READY";
        return new EmbeddingRouteSnapshot(
                record.get("route_id", UUID.class),
                scope.tenantId(),
                scope.workspaceId(),
                record.get("route_name", String.class),
                record.get("route_version", Long.class),
                record.get("model_id", UUID.class),
                ModelRouteCapability.EMBEDDING,
                ModelRouteStatus.valueOf(routeStatus),
                record.get("timeout_ms", Integer.class),
                new EmbeddingRouteProfile(
                        record.get("dimension", Integer.class),
                        record.get("input_limit", Integer.class),
                        record.get("batch_limit", Integer.class),
                        EmbeddingNormalization.valueOf(record.get("normalization", String.class))),
                ready,
                readinessCode,
                record.get("created_at", OffsetDateTime.class));
    }
}
