package io.apvero.platform.governance.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import io.apvero.platform.governance.AuditEvent;
import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.governance.BudgetExceededException;
import io.apvero.platform.governance.BudgetPolicy;
import io.apvero.platform.governance.BudgetPolicyCatalog;
import io.apvero.platform.governance.BudgetScopeType;
import io.apvero.platform.governance.CreateBudgetPolicyCommand;
import io.apvero.platform.governance.ExecutionAdmission;
import io.apvero.platform.governance.ExecutionGovernance;
import io.apvero.platform.governance.RateLimitExceededException;
import io.apvero.platform.governance.RetentionPolicy;
import io.apvero.platform.governance.RetentionPolicyCatalog;
import io.apvero.platform.governance.GovernanceMaintenance;
import io.apvero.platform.governance.RetentionTarget;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class DefaultGovernanceCatalog implements BudgetPolicyCatalog, RetentionPolicyCatalog,
        AuditEventCatalog, ExecutionGovernance, GovernanceMaintenance {
    private final DSLContext sql;
    private final WorkspaceScopeCatalog workspaces;
    private final ObjectMapper json;
    private final PolicyDecisionAudit policyAudit;

    public DefaultGovernanceCatalog(DSLContext sql, WorkspaceScopeCatalog workspaces, ObjectMapper json,
            PolicyDecisionAudit policyAudit) {
        this.sql = sql;
        this.workspaces = workspaces;
        this.json = json;
        this.policyAudit = policyAudit;
    }

    @Override
    public List<BudgetPolicy> listBudgets(UUID workspaceId) {
        workspaces.require(workspaceId);
        return sql.select(
                        field("id", UUID.class), field("tenant_id", UUID.class), field("workspace_id", UUID.class),
                        field("name", String.class), field("scope_type", String.class), field("scope_id", UUID.class),
                        field("monthly_cost_limit_micros", Long.class), field("requests_per_minute", Integer.class),
                        field("enabled", Boolean.class), field("created_at", OffsetDateTime.class),
                        field("updated_at", OffsetDateTime.class))
                .from(table("budget_policy"))
                .where(field("workspace_id", UUID.class).eq(workspaceId))
                .orderBy(field("updated_at").desc())
                .fetch(record -> new BudgetPolicy(record.value1(), record.value2(), record.value3(), record.value4(),
                        BudgetScopeType.valueOf(record.value5()), record.value6(), record.value7(), record.value8(),
                        Boolean.TRUE.equals(record.value9()), record.value10(), record.value11()));
    }

    @Override
    @Transactional
    public BudgetPolicy create(UUID workspaceId, CreateBudgetPolicyCommand command) {
        if (command.name() == null || command.name().isBlank() || command.name().length() > 160) {
            throw new IllegalArgumentException("Budget policy name is required and must not exceed 160 characters.");
        }
        if (command.scopeType() == null) throw new IllegalArgumentException("Budget scope is required.");
        boolean workspaceScope = command.scopeType() == BudgetScopeType.WORKSPACE;
        if (workspaceScope != (command.scopeId() == null)) throw new IllegalArgumentException("Budget scope identifier is invalid.");
        if (command.monthlyCostLimitMicros() == null && command.requestsPerMinute() == null) {
            throw new IllegalArgumentException("At least one budget limit is required.");
        }
        if (command.monthlyCostLimitMicros() != null && command.monthlyCostLimitMicros() < 0) {
            throw new IllegalArgumentException("Monthly cost limit cannot be negative.");
        }
        if (command.requestsPerMinute() != null && command.requestsPerMinute() < 1) {
            throw new IllegalArgumentException("Requests per minute must be positive.");
        }
        WorkspaceScope scope = workspaces.require(workspaceId);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(table("budget_policy"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("name"),
                        field("scope_type"), field("scope_id"), field("monthly_cost_limit_micros"),
                        field("requests_per_minute"), field("enabled"), field("created_at"), field("updated_at"))
                .values(id, scope.tenantId(), workspaceId, command.name().trim(), command.scopeType().name(),
                        command.scopeId(), command.monthlyCostLimitMicros(), command.requestsPerMinute(), true, now, now)
                .execute();
        return listBudgets(workspaceId).stream().filter(policy -> policy.id().equals(id)).findFirst().orElseThrow();
    }

    @Override
    public RetentionPolicy get(UUID workspaceId) {
        WorkspaceScope scope = workspaces.require(workspaceId);
        return sql.select(field("workspace_id", UUID.class), field("tenant_id", UUID.class),
                        field("run_retention_days", Integer.class), field("audit_retention_days", Integer.class),
                        field("retain_payloads", Boolean.class), field("mask_sensitive_fields", Boolean.class),
                        field("version", Long.class), field("created_at", OffsetDateTime.class),
                        field("updated_at", OffsetDateTime.class))
                .from(table("retention_policy"))
                .where(field("workspace_id", UUID.class).eq(workspaceId))
                .fetchOptional(record -> new RetentionPolicy(record.value1(), record.value2(), record.value3(),
                        record.value4(), Boolean.TRUE.equals(record.value5()), Boolean.TRUE.equals(record.value6()),
                        record.value7(), record.value8(), record.value9()))
                .orElse(new RetentionPolicy(workspaceId, scope.tenantId(), 90, 365, true, true, 0,
                        OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Override
    @Transactional
    public RetentionPolicy update(UUID workspaceId, int runRetentionDays, int auditRetentionDays,
            boolean retainPayloads, boolean maskSensitiveFields) {
        if (runRetentionDays < 1 || runRetentionDays > 3650 || auditRetentionDays < 30 || auditRetentionDays > 3650) {
            throw new IllegalArgumentException("Retention periods are outside the supported range.");
        }
        WorkspaceScope scope = workspaces.require(workspaceId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(table("retention_policy"))
                .columns(field("workspace_id"), field("tenant_id"), field("run_retention_days"),
                        field("audit_retention_days"), field("retain_payloads"), field("mask_sensitive_fields"),
                        field("version"), field("created_at"), field("updated_at"))
                .values(workspaceId, scope.tenantId(), runRetentionDays, auditRetentionDays, retainPayloads,
                        maskSensitiveFields, 1L, now, now)
                .onConflict(field("workspace_id"))
                .doUpdate()
                .set(field("run_retention_days"), runRetentionDays)
                .set(field("audit_retention_days"), auditRetentionDays)
                .set(field("retain_payloads"), retainPayloads)
                .set(field("mask_sensitive_fields"), maskSensitiveFields)
                .set(field("version"), field("retention_policy.version", Long.class).add(1L))
                .set(field("updated_at"), now)
                .execute();
        return get(workspaceId);
    }

    @Override
    public List<AuditEvent> listAuditEvents(UUID workspaceId) {
        workspaces.require(workspaceId);
        return sql.select(field("id", UUID.class), field("tenant_id", UUID.class), field("workspace_id", UUID.class),
                        field("occurred_at", OffsetDateTime.class), field("actor_id", String.class),
                        field("action", String.class), field("resource_type", String.class),
                        field("resource_id", String.class), field("outcome", String.class),
                        field("source_ip", String.class), field("trace_id", String.class), field("details", JSONB.class))
                .from(table("audit_event"))
                .where(field("workspace_id", UUID.class).eq(workspaceId))
                .orderBy(field("occurred_at").desc())
                .limit(500)
                .fetch(record -> new AuditEvent(record.value1(), record.value2(), record.value3(), record.value4(),
                        record.value5(), record.value6(), record.value7(), record.value8(), record.value9(),
                        record.value10(), record.value11(), readJson(record.value12())));
    }

    @Override
    @Transactional
    public void append(UUID workspaceId, String actorId, String action, String resourceType,
            String resourceId, String outcome, String sourceIp, String traceId) {
        WorkspaceScope scope = workspaces.require(workspaceId);
        sql.insertInto(table("audit_event"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("occurred_at"),
                        field("actor_id"), field("action"), field("resource_type"), field("resource_id"),
                        field("outcome"), field("source_ip"), field("trace_id"), field("details"))
                .values(UUID.randomUUID(), scope.tenantId(), workspaceId, OffsetDateTime.now(ZoneOffset.UTC),
                        safe(actorId, "anonymous"), safe(action, "unknown"), safe(resourceType, "api"), resourceId,
                        outcome, sourceIp, traceId, JSONB.valueOf("{}"))
                .execute();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionAdmission admit(UUID workspaceId, UUID applicationId, UUID modelRouteId,
            String actorId, String traceId, long estimatedCostMicros) {
        WorkspaceScope scope = workspaces.require(workspaceId);
        sql.fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", workspaceId.toString());
        List<BudgetPolicy> policies = listBudgets(workspaceId).stream()
                .filter(BudgetPolicy::enabled)
                .filter(policy -> matches(policy, applicationId, modelRouteId))
                .toList();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime minute = now.withSecond(0).withNano(0);
        OffsetDateTime month = now.withDayOfMonth(1).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        for (BudgetPolicy policy : policies) {
            if (policy.requestsPerMinute() != null) {
                int count = sql.fetchOne("""
                        insert into rate_limit_counter(policy_id, window_started_at, request_count)
                        values (?, ?::timestamptz, 1)
                        on conflict (policy_id, window_started_at)
                        do update set request_count = rate_limit_counter.request_count + 1
                        returning request_count
                        """, policy.id(), minute).get("request_count", Integer.class);
                if (count > policy.requestsPerMinute()) {
                    policyAudit.denied(scope.tenantId(), workspaceId, applicationId, actorId, traceId,
                            "RATE_LIMIT_EXCEEDED");
                    throw new RateLimitExceededException();
                }
            }
            if (policy.monthlyCostLimitMicros() != null) {
                Long consumed = sql.fetchOne("""
                        select coalesce(sum(coalesce(actual_cost_micros, estimated_cost_micros)), 0)
                        from execution_reservation
                        where workspace_id = ? and created_at >= ?::timestamptz
                          and (? = 'WORKSPACE'
                            or (? = 'APPLICATION' and application_id = ?)
                            or (? = 'MODEL_ROUTE' and model_route_id = ?))
                        """, workspaceId, month, policy.scopeType().name(), policy.scopeType().name(), applicationId,
                        policy.scopeType().name(), modelRouteId).get(0, Long.class);
                if (consumed + estimatedCostMicros > policy.monthlyCostLimitMicros()) {
                    policyAudit.denied(scope.tenantId(), workspaceId, applicationId, actorId, traceId,
                            "BUDGET_EXCEEDED");
                    throw new BudgetExceededException();
                }
            }
        }
        UUID reservationId = UUID.randomUUID();
        sql.insertInto(table("execution_reservation"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("application_id"),
                        field("model_route_id"), field("actor_id"), field("trace_id"),
                        field("estimated_cost_micros"), field("status"), field("created_at"))
                .values(reservationId, scope.tenantId(), workspaceId, applicationId, modelRouteId,
                        safe(actorId, "system"), traceId, estimatedCostMicros, "RESERVED", now)
                .execute();
        RetentionPolicy retention = get(workspaceId);
        return new ExecutionAdmission(reservationId, retention.retainPayloads(), retention.maskSensitiveFields());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(UUID reservationId, long actualCostMicros, boolean succeeded) {
        int changed = sql.update(table("execution_reservation"))
                .set(field("actual_cost_micros"), Math.max(0, actualCostMicros))
                .set(field("status"), succeeded ? "SUCCEEDED" : "FAILED")
                .set(field("settled_at"), OffsetDateTime.now(ZoneOffset.UTC))
                .where(field("id", UUID.class).eq(reservationId).and(field("status", String.class).eq("RESERVED")))
                .execute();
        if (changed != 1) throw new IllegalStateException("Execution reservation is missing or already settled.");
    }

    @Override
    public List<RetentionTarget> retentionTargets() {
        return sql.select(field("workspace_id", UUID.class), field("run_retention_days", Integer.class),
                        field("audit_retention_days", Integer.class))
                .from(table("retention_policy"))
                .fetch(record -> new RetentionTarget(record.value1(), record.value2(), record.value3()));
    }

    @Override
    @Transactional
    public int purgeAuditBefore(UUID workspaceId, OffsetDateTime cutoff) {
        sql.execute("set local apvero.retention_purge = 'on'");
        return sql.deleteFrom(table("audit_event"))
                .where(field("workspace_id", UUID.class).eq(workspaceId)
                        .and(field("occurred_at", OffsetDateTime.class).lt(cutoff)))
                .execute();
    }

    @Override
    @Transactional
    public int purgeRateCountersBefore(OffsetDateTime cutoff) {
        return sql.deleteFrom(table("rate_limit_counter"))
                .where(field("window_started_at", OffsetDateTime.class).lt(cutoff))
                .execute();
    }

    @Override
    @Transactional
    public int reconcileStaleReservationsBefore(OffsetDateTime cutoff) {
        return sql.update(table("execution_reservation"))
                .set(field("status"), "FAILED")
                .set(field("actual_cost_micros"), 0L)
                .set(field("settled_at"), OffsetDateTime.now(ZoneOffset.UTC))
                .where(field("status", String.class).eq("RESERVED")
                        .and(field("created_at", OffsetDateTime.class).lt(cutoff)))
                .execute();
    }

    private boolean matches(BudgetPolicy policy, UUID applicationId, UUID modelRouteId) {
        return switch (policy.scopeType()) {
            case WORKSPACE -> true;
            case APPLICATION -> applicationId.equals(policy.scopeId());
            case MODEL_ROUTE -> modelRouteId.equals(policy.scopeId());
        };
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.substring(0, Math.min(value.length(), 160));
    }

    private tools.jackson.databind.JsonNode readJson(JSONB value) {
        try {
            return json.readTree(value.data());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored audit details are invalid JSON.", exception);
        }
    }
}
