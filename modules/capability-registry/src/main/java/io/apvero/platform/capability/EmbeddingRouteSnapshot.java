package io.apvero.platform.capability;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record EmbeddingRouteSnapshot(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        String name,
        long version,
        UUID modelId,
        ModelRouteCapability routeCapability,
        ModelRouteStatus status,
        int timeoutMs,
        EmbeddingRouteProfile profile,
        boolean ready,
        String readinessCode,
        OffsetDateTime createdAt) {

    public EmbeddingRouteSnapshot {
        Objects.requireNonNull(id, "APVERO_EMBEDDING_ROUTE_ID_REQUIRED");
        Objects.requireNonNull(tenantId, "APVERO_TENANT_ID_REQUIRED");
        Objects.requireNonNull(workspaceId, "APVERO_WORKSPACE_ID_REQUIRED");
        Objects.requireNonNull(modelId, "APVERO_MODEL_ID_REQUIRED");
        Objects.requireNonNull(routeCapability, "APVERO_MODEL_ROUTE_CAPABILITY_REQUIRED");
        Objects.requireNonNull(status, "APVERO_EMBEDDING_ROUTE_STATUS_REQUIRED");
        Objects.requireNonNull(profile, "APVERO_EMBEDDING_PROFILE_REQUIRED");
        Objects.requireNonNull(createdAt, "APVERO_EMBEDDING_ROUTE_CREATED_AT_REQUIRED");
        if (name == null || name.isBlank() || name.length() > 160) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_ROUTE_NAME_INVALID");
        }
        if (version < 1) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_ROUTE_VERSION_INVALID");
        }
        if (routeCapability != ModelRouteCapability.EMBEDDING) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_ROUTE_CAPABILITY_INVALID");
        }
        if (timeoutMs < 1_000 || timeoutMs > 300_000) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_ROUTE_TIMEOUT_INVALID");
        }
        if (readinessCode == null || readinessCode.isBlank() || readinessCode.length() > 120) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_READINESS_CODE_INVALID");
        }
        name = name.trim();
        readinessCode = readinessCode.trim();
    }

    public String reference() {
        return name + "@" + version;
    }

    public boolean availableForNewBuilds() {
        return ready && status == ModelRouteStatus.PUBLISHED;
    }
}
