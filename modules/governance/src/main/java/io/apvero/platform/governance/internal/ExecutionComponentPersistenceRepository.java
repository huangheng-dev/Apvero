package io.apvero.platform.governance.internal;

import io.apvero.platform.identity.WorkspaceScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ExecutionComponentPersistenceRepository {
    ExecutionComponentPersistenceRecord insert(
            WorkspaceScope scope, ExecutionComponentPersistenceRecord row);

    Optional<ExecutionComponentPersistenceRecord> find(
            WorkspaceScope scope, UUID componentId);

    Optional<ExecutionComponentPersistenceRecord> findByIdentity(
            WorkspaceScope scope, UUID reservationId, String idempotencyIdentity);

    List<ExecutionComponentPersistenceRecord> listByReservation(
            WorkspaceScope scope, UUID reservationId);

    ExecutionComponentPersistenceRecord markDispatched(
            WorkspaceScope scope,
            UUID reservationId,
            String idempotencyIdentity,
            String providerRequestIdentity,
            java.time.OffsetDateTime now);

    ExecutionComponentPersistenceRecord settle(
            WorkspaceScope scope,
            UUID reservationId,
            String idempotencyIdentity,
            long actualUnits,
            long actualCostMicros,
            String currency,
            String usageQuality,
            boolean succeeded,
            String failureCode,
            java.time.OffsetDateTime now);

    ExecutionComponentPersistenceRecord requireReconciliation(
            WorkspaceScope scope,
            UUID reservationId,
            String idempotencyIdentity,
            String failureCode,
            java.time.OffsetDateTime now);
}
