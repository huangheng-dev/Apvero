package io.apvero.platform.governance.internal;

import io.apvero.platform.identity.WorkspaceScope;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
class JooqExecutionComponentPersistenceRepository
        implements ExecutionComponentPersistenceRepository {
    private static final String SELECT = """
            select id, tenant_id, workspace_id, reservation_id, component_type,
                model_route_id, model_route_reference, idempotency_identity,
                estimated_units, actual_units, usage_quality, estimated_cost_micros,
                actual_cost_micros, currency, status, provider_request_identity,
                failure_code, dispatched_at, settled_at, created_at, updated_at
            from execution_reservation_component
            """;

    private final DSLContext sql;

    JooqExecutionComponentPersistenceRepository(DSLContext sql) {
        this.sql = sql;
    }

    @Override
    public ExecutionComponentPersistenceRecord insert(
            WorkspaceScope scope, ExecutionComponentPersistenceRecord row) {
        requireScope(scope, row.tenantId(), row.workspaceId());
        sql.execute("""
                insert into execution_reservation_component(
                    id, tenant_id, workspace_id, reservation_id, component_type,
                    model_route_id, model_route_reference, idempotency_identity,
                    estimated_units, actual_units, usage_quality, estimated_cost_micros,
                    actual_cost_micros, currency, status, provider_request_identity,
                    failure_code, dispatched_at, settled_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.workspaceId(), row.reservationId(),
                row.componentType(), row.modelRouteId(), row.modelRouteReference(),
                row.idempotencyIdentity(), row.estimatedUnits(), row.actualUnits(),
                row.usageQuality(), row.estimatedCostMicros(), row.actualCostMicros(),
                row.currency(), row.status(), row.providerRequestIdentity(), row.failureCode(),
                timestamp(row.dispatchedAt()), timestamp(row.settledAt()),
                timestamp(row.createdAt()), timestamp(row.updatedAt()));
        return find(scope, row.id()).orElseThrow();
    }

    @Override
    public Optional<ExecutionComponentPersistenceRecord> find(
            WorkspaceScope scope, UUID componentId) {
        return sql.fetchOptional(SELECT
                        + " where tenant_id = ? and workspace_id = ? and id = ?",
                        scope.tenantId(), scope.workspaceId(), componentId)
                .map(this::map);
    }

    @Override
    public Optional<ExecutionComponentPersistenceRecord> findByIdentity(
            WorkspaceScope scope, UUID reservationId, String idempotencyIdentity) {
        return sql.fetchOptional(SELECT
                        + """
                         where tenant_id = ? and workspace_id = ?
                           and reservation_id = ? and idempotency_identity = ?
                         """, scope.tenantId(), scope.workspaceId(), reservationId,
                        idempotencyIdentity)
                .map(this::map);
    }

    @Override
    public List<ExecutionComponentPersistenceRecord> listByReservation(
            WorkspaceScope scope, UUID reservationId) {
        return sql.fetch(SELECT
                        + """
                         where tenant_id = ? and workspace_id = ? and reservation_id = ?
                         order by created_at, id
                         """, scope.tenantId(), scope.workspaceId(), reservationId)
                .map(this::map);
    }

    @Override
    public ExecutionComponentPersistenceRecord markDispatched(
            WorkspaceScope scope,
            UUID reservationId,
            String idempotencyIdentity,
            String providerRequestIdentity,
            OffsetDateTime now) {
        ExecutionComponentPersistenceRecord current =
                lock(scope, reservationId, idempotencyIdentity);
        if (!"RESERVED".equals(current.status()) && !"DISPATCHED".equals(current.status())) {
            throw conflict("APVERO_EXECUTION_COMPONENT_DISPATCH_CONFLICT");
        }
        String storedIdentity = current.providerRequestIdentity();
        if (storedIdentity != null
                && providerRequestIdentity != null
                && !storedIdentity.equals(providerRequestIdentity)) {
            throw conflict("APVERO_EXECUTION_PROVIDER_IDENTITY_CONFLICT");
        }
        String effectiveIdentity = storedIdentity == null ? providerRequestIdentity : storedIdentity;
        if ("RESERVED".equals(current.status())) {
            sql.execute("""
                    update execution_reservation_component
                    set status = 'DISPATCHED', provider_request_identity = ?,
                        dispatched_at = ?, updated_at = ?
                    where tenant_id = ? and workspace_id = ? and reservation_id = ?
                      and idempotency_identity = ? and status = 'RESERVED'
                    """, effectiveIdentity, timestamp(now), timestamp(now), scope.tenantId(),
                    scope.workspaceId(), reservationId, idempotencyIdentity);
        } else if (storedIdentity == null && effectiveIdentity != null) {
            sql.execute("""
                    update execution_reservation_component
                    set provider_request_identity = ?, updated_at = ?
                    where tenant_id = ? and workspace_id = ? and reservation_id = ?
                      and idempotency_identity = ? and status = 'DISPATCHED'
                      and provider_request_identity is null
                    """, effectiveIdentity, timestamp(now), scope.tenantId(), scope.workspaceId(),
                    reservationId, idempotencyIdentity);
        }
        return findByIdentity(scope, reservationId, idempotencyIdentity).orElseThrow();
    }

    @Override
    public ExecutionComponentPersistenceRecord settle(
            WorkspaceScope scope,
            UUID reservationId,
            String idempotencyIdentity,
            long actualUnits,
            long actualCostMicros,
            String currency,
            String usageQuality,
            boolean succeeded,
            String failureCode,
            OffsetDateTime now) {
        ExecutionComponentPersistenceRecord current =
                lock(scope, reservationId, idempotencyIdentity);
        String targetStatus = succeeded ? "SUCCEEDED" : "FAILED";
        if (!currency.equals(current.currency())) {
            throw conflict("APVERO_EXECUTION_COMPONENT_SETTLEMENT_CONFLICT");
        }
        if (targetStatus.equals(current.status())) {
            if (equalsSettlement(current, actualUnits, actualCostMicros, currency,
                    usageQuality, failureCode)) {
                return current;
            }
            throw conflict("APVERO_EXECUTION_COMPONENT_SETTLEMENT_CONFLICT");
        }
        if (!"DISPATCHED".equals(current.status())) {
            throw conflict("APVERO_EXECUTION_COMPONENT_SETTLEMENT_CONFLICT");
        }
        int changed = sql.execute("""
                update execution_reservation_component
                set status = ?, actual_units = ?, actual_cost_micros = ?, currency = ?,
                    usage_quality = ?, failure_code = ?, settled_at = ?, updated_at = ?
                where tenant_id = ? and workspace_id = ? and reservation_id = ?
                  and idempotency_identity = ? and status = 'DISPATCHED'
                """, targetStatus, actualUnits, actualCostMicros, currency, usageQuality,
                failureCode, timestamp(now), timestamp(now), scope.tenantId(), scope.workspaceId(),
                reservationId, idempotencyIdentity);
        if (changed != 1) {
            throw conflict("APVERO_EXECUTION_COMPONENT_SETTLEMENT_CONFLICT");
        }
        return findByIdentity(scope, reservationId, idempotencyIdentity).orElseThrow();
    }

    @Override
    public ExecutionComponentPersistenceRecord requireReconciliation(
            WorkspaceScope scope,
            UUID reservationId,
            String idempotencyIdentity,
            String failureCode,
            OffsetDateTime now) {
        ExecutionComponentPersistenceRecord current =
                lock(scope, reservationId, idempotencyIdentity);
        if ("RECONCILIATION_REQUIRED".equals(current.status())) {
            if (failureCode.equals(current.failureCode())) {
                return current;
            }
            throw conflict("APVERO_EXECUTION_COMPONENT_RECONCILIATION_CONFLICT");
        }
        if (!"DISPATCHED".equals(current.status())) {
            throw conflict("APVERO_EXECUTION_COMPONENT_RECONCILIATION_CONFLICT");
        }
        int changed = sql.execute("""
                update execution_reservation_component
                set status = 'RECONCILIATION_REQUIRED', failure_code = ?, updated_at = ?
                where tenant_id = ? and workspace_id = ? and reservation_id = ?
                  and idempotency_identity = ? and status = 'DISPATCHED'
                """, failureCode, timestamp(now), scope.tenantId(), scope.workspaceId(),
                reservationId, idempotencyIdentity);
        if (changed != 1) {
            throw conflict("APVERO_EXECUTION_COMPONENT_RECONCILIATION_CONFLICT");
        }
        return findByIdentity(scope, reservationId, idempotencyIdentity).orElseThrow();
    }

    private ExecutionComponentPersistenceRecord lock(
            WorkspaceScope scope, UUID reservationId, String idempotencyIdentity) {
        return sql.fetchOptional(SELECT
                        + """
                         where tenant_id = ? and workspace_id = ?
                           and reservation_id = ? and idempotency_identity = ?
                         for update
                         """, scope.tenantId(), scope.workspaceId(), reservationId,
                        idempotencyIdentity)
                .map(this::map)
                .orElseThrow(() -> conflict("APVERO_EXECUTION_COMPONENT_NOT_FOUND"));
    }

    private static boolean equalsSettlement(
            ExecutionComponentPersistenceRecord current,
            long actualUnits,
            long actualCostMicros,
            String currency,
            String usageQuality,
            String failureCode) {
        return Long.valueOf(actualUnits).equals(current.actualUnits())
                && Long.valueOf(actualCostMicros).equals(current.actualCostMicros())
                && currency.equals(current.currency())
                && usageQuality.equals(current.usageQuality())
                && java.util.Objects.equals(failureCode, current.failureCode());
    }

    private static IllegalStateException conflict(String code) {
        return new IllegalStateException(code);
    }

    private ExecutionComponentPersistenceRecord map(Record record) {
        return new ExecutionComponentPersistenceRecord(
                uuid(record, "id"), uuid(record, "tenant_id"), uuid(record, "workspace_id"),
                uuid(record, "reservation_id"), string(record, "component_type"),
                uuid(record, "model_route_id"), string(record, "model_route_reference"),
                string(record, "idempotency_identity"), longValue(record, "estimated_units"),
                longValue(record, "actual_units"), string(record, "usage_quality"),
                longValue(record, "estimated_cost_micros"),
                longValue(record, "actual_cost_micros"), string(record, "currency"),
                string(record, "status"), string(record, "provider_request_identity"),
                string(record, "failure_code"), time(record, "dispatched_at"),
                time(record, "settled_at"), time(record, "created_at"), time(record, "updated_at"));
    }

    private static void requireScope(WorkspaceScope scope, UUID tenantId, UUID workspaceId) {
        if (!scope.tenantId().equals(tenantId) || !scope.workspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("APVERO_GOVERNANCE_SCOPE_MISMATCH");
        }
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static UUID uuid(Record record, String field) {
        return record.get(field, UUID.class);
    }

    private static String string(Record record, String field) {
        return record.get(field, String.class);
    }

    private static Long longValue(Record record, String field) {
        return record.get(field, Long.class);
    }

    private static OffsetDateTime time(Record record, String field) {
        return record.get(field, OffsetDateTime.class);
    }
}
