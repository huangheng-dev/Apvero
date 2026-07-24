package io.apvero.platform.governance.internal;

import java.time.OffsetDateTime;
import java.util.UUID;

record ExecutionComponentPersistenceRecord(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        UUID reservationId,
        String componentType,
        UUID modelRouteId,
        String modelRouteReference,
        String idempotencyIdentity,
        long estimatedUnits,
        Long actualUnits,
        String usageQuality,
        long estimatedCostMicros,
        Long actualCostMicros,
        String currency,
        String status,
        String providerRequestIdentity,
        String failureCode,
        OffsetDateTime dispatchedAt,
        OffsetDateTime settledAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
