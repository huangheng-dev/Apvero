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
    public List<ExecutionComponentPersistenceRecord> listByReservation(
            WorkspaceScope scope, UUID reservationId) {
        return sql.fetch(SELECT
                        + """
                         where tenant_id = ? and workspace_id = ? and reservation_id = ?
                         order by created_at, id
                         """, scope.tenantId(), scope.workspaceId(), reservationId)
                .map(this::map);
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
