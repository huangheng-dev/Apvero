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
import io.apvero.platform.governance.ExecutionComponentDispatch;
import io.apvero.platform.governance.ExecutionComponentReconciliation;
import io.apvero.platform.governance.ExecutionComponentRequest;
import io.apvero.platform.governance.ExecutionComponentSettlement;
import io.apvero.platform.governance.ExecutionComponentSnapshot;
import io.apvero.platform.governance.ExecutionComponentState;
import io.apvero.platform.governance.ExecutionComponentType;
import io.apvero.platform.governance.ExecutionGovernance;
import io.apvero.platform.governance.ExecutionReservationRequest;
import io.apvero.platform.governance.ExecutionSubjectType;
import io.apvero.platform.governance.ExecutionUsageQuality;
import io.apvero.platform.governance.RateLimitExceededException;
import io.apvero.platform.governance.RetentionPolicy;
import io.apvero.platform.governance.RetentionPolicyCatalog;
import io.apvero.platform.governance.GovernanceMaintenance;
import io.apvero.platform.governance.RetentionTarget;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final ExecutionComponentPersistenceRepository components;

    public DefaultGovernanceCatalog(DSLContext sql, WorkspaceScopeCatalog workspaces, ObjectMapper json,
            PolicyDecisionAudit policyAudit, ExecutionComponentPersistenceRepository components) {
        this.sql = sql;
        this.workspaces = workspaces;
        this.json = json;
        this.policyAudit = policyAudit;
        this.components = components;
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
    public RetentionPolicy getOrCreate(UUID workspaceId) {
        WorkspaceScope scope = workspaces.require(workspaceId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(table("retention_policy"))
                .columns(field("workspace_id"), field("tenant_id"), field("run_retention_days"),
                        field("audit_retention_days"), field("retain_payloads"), field("mask_sensitive_fields"),
                        field("version"), field("created_at"), field("updated_at"))
                .values(workspaceId, scope.tenantId(), 90, 365, true, true, 1L, now, now)
                .onConflict(field("workspace_id"))
                .doNothing()
                .execute();
        RetentionPolicy current = get(workspaceId);
        if (current.version() < 1) {
            throw new IllegalStateException("APVERO_RETENTION_POLICY_VERSION_INVALID");
        }
        return current;
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
        appendInternal(
                workspaceId, actorId, action, resourceType, resourceId, outcome, sourceIp, traceId,
                JSONB.valueOf("{}"));
    }

    @Override
    @Transactional
    public void appendWithDigest(UUID workspaceId, String actorId, String action, String resourceType,
            String resourceId, String outcome, String sourceIp, String traceId, String digest) {
        if (digest == null || !digest.matches("^sha256:[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("APVERO_AUDIT_DIGEST_INVALID");
        }
        appendInternal(
                workspaceId, actorId, action, resourceType, resourceId, outcome, sourceIp, traceId,
                JSONB.valueOf("{\"digest\":\"" + digest + "\"}"));
    }

    private void appendInternal(
            UUID workspaceId,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String sourceIp,
            String traceId,
            JSONB details) {
        WorkspaceScope scope = workspaces.require(workspaceId);
        sql.insertInto(table("audit_event"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("occurred_at"),
                        field("actor_id"), field("action"), field("resource_type"), field("resource_id"),
                        field("outcome"), field("source_ip"), field("trace_id"), field("details"))
                .values(UUID.randomUUID(), scope.tenantId(), workspaceId, OffsetDateTime.now(ZoneOffset.UTC),
                        safe(actorId, "anonymous"), safe(action, "unknown"), safe(resourceType, "api"), resourceId,
                        outcome, sourceIp, traceId, details)
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
                    policyAudit.denied(scope.tenantId(), workspaceId, applicationId, "APPLICATION_RUN",
                            actorId, traceId,
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
                    policyAudit.denied(scope.tenantId(), workspaceId, applicationId, "APPLICATION_RUN",
                            actorId, traceId,
                            "BUDGET_EXCEEDED");
                    throw new BudgetExceededException();
                }
            }
        }
        UUID reservationId = UUID.randomUUID();
        sql.insertInto(table("execution_reservation"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("application_id"),
                        field("subject_type"), field("subject_id"), field("model_route_id"),
                        field("actor_id"), field("trace_id"),
                        field("estimated_cost_micros"), field("status"), field("created_at"))
                .values(reservationId, scope.tenantId(), workspaceId, applicationId,
                        "APPLICATION_RUN", applicationId, modelRouteId,
                        safe(actorId, "system"), traceId, estimatedCostMicros, "RESERVED", now)
                .execute();
        RetentionPolicy retention = get(workspaceId);
        return new ExecutionAdmission(reservationId, retention.retainPayloads(), retention.maskSensitiveFields());
    }

    @Override
    @Transactional
    public ExecutionAdmission admit(ExecutionReservationRequest request) {
        WorkspaceScope scope = workspaces.require(request.workspaceId());
        sql.fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                request.workspaceId().toString());
        sql.fetch("select pg_advisory_xact_lock(hashtextextended(?, 1))", request.traceId());
        validateRoutes(scope, request.components());
        UUID existingReservationId = findIdempotentReservation(scope, request);
        if (existingReservationId != null) {
            return admission(existingReservationId, request.workspaceId());
        }
        if (sql.fetchExists(sql.selectOne()
                .from(table("execution_reservation"))
                .where(field("trace_id", String.class).eq(request.traceId())))) {
            throw conflict("APVERO_EXECUTION_RESERVATION_IDEMPOTENCY_CONFLICT");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        evaluateComponentPolicies(scope, request, now);
        UUID reservationId = UUID.randomUUID();
        UUID applicationId = request.subject().type() == ExecutionSubjectType.APPLICATION_RUN
                ? request.subject().id() : null;
        UUID representativeRouteId = request.components().getFirst().modelRouteId();
        sql.insertInto(table("execution_reservation"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"),
                        field("application_id"), field("subject_type"), field("subject_id"),
                        field("model_route_id"), field("actor_id"), field("trace_id"),
                        field("estimated_cost_micros"), field("status"), field("created_at"))
                .values(reservationId, scope.tenantId(), scope.workspaceId(), applicationId,
                        request.subject().type().name(), request.subject().id(),
                        representativeRouteId, request.actorId(), request.traceId(),
                        request.estimatedCostMicros(), "RESERVED", now)
                .execute();
        for (ExecutionComponentRequest component : request.components()) {
            components.insert(scope, new ExecutionComponentPersistenceRecord(
                    UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), reservationId,
                    component.type().name(), component.modelRouteId(),
                    component.modelRouteReference(), component.idempotencyIdentity(),
                    component.estimatedUnits(), null, null, component.estimatedCostMicros(),
                    null, component.currency(), "RESERVED", null, null, null, null, now, now));
        }
        return admission(reservationId, request.workspaceId());
    }

    @Override
    @Transactional
    public void markDispatched(ExecutionComponentDispatch dispatch) {
        WorkspaceScope scope = scopeForReservation(dispatch.reservationId());
        components.markDispatched(scope, dispatch.reservationId(),
                dispatch.idempotencyIdentity(), dispatch.providerRequestIdentity(),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Override
    @Transactional
    public void settle(ExecutionComponentSettlement settlement) {
        WorkspaceScope scope = scopeForReservation(settlement.reservationId());
        components.settle(scope, settlement.reservationId(), settlement.idempotencyIdentity(),
                settlement.actualUnits(), settlement.actualCostMicros(), settlement.currency(),
                settlement.usageQuality().name(), settlement.succeeded(), settlement.failureCode(),
                OffsetDateTime.now(ZoneOffset.UTC));
        aggregateParent(scope, settlement.reservationId());
    }

    @Override
    @Transactional
    public void requireReconciliation(ExecutionComponentReconciliation reconciliation) {
        WorkspaceScope scope = scopeForReservation(reconciliation.reservationId());
        components.requireReconciliation(scope, reconciliation.reservationId(),
                reconciliation.idempotencyIdentity(), reconciliation.failureCode(),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public Optional<ExecutionComponentSnapshot> findComponent(
            UUID workspaceId,
            UUID reservationId,
            String idempotencyIdentity) {
        Objects.requireNonNull(workspaceId, "APVERO_WORKSPACE_ID_REQUIRED");
        Objects.requireNonNull(reservationId, "APVERO_EXECUTION_RESERVATION_ID_REQUIRED");
        if (idempotencyIdentity == null || idempotencyIdentity.isBlank()) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_IDEMPOTENCY_INVALID");
        }
        WorkspaceScope scope = workspaces.require(workspaceId);
        return components.findByIdentity(scope, reservationId, idempotencyIdentity.trim())
                .map(DefaultGovernanceCatalog::snapshot);
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
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int reconciliationRequired = sql.execute("""
                update execution_reservation_component
                set status = 'RECONCILIATION_REQUIRED',
                    failure_code = 'APVERO_EXECUTION_STALE_DISPATCH',
                    updated_at = ?::timestamptz
                where status = 'DISPATCHED' and updated_at < ?::timestamptz
                """, now, cutoff);
        int legacyFailed = sql.update(table("execution_reservation"))
                .set(field("status"), "FAILED")
                .set(field("actual_cost_micros"), 0L)
                .set(field("settled_at"), now)
                .where(field("status", String.class).eq("RESERVED")
                        .and(field("created_at", OffsetDateTime.class).lt(cutoff))
                        .andNotExists(sql.selectOne()
                                .from(table("execution_reservation_component"))
                                .where(field("execution_reservation_component.reservation_id",
                                        UUID.class).eq(field("execution_reservation.id", UUID.class)))))
                .execute();
        return reconciliationRequired + legacyFailed;
    }

    private void validateRoutes(WorkspaceScope scope, List<ExecutionComponentRequest> requested) {
        for (ExecutionComponentRequest component : requested) {
            String requiredCapability = component.type() == ExecutionComponentType.CHAT_GENERATION
                    ? "CHAT" : "EMBEDDING";
            Integer count = sql.fetchOne("""
                    select count(*)
                    from model_route route
                    join model_definition model
                      on model.id = route.model_id
                     and model.tenant_id = route.tenant_id
                     and model.workspace_id = route.workspace_id
                    join model_provider provider
                      on provider.id = model.provider_id
                     and provider.tenant_id = model.tenant_id
                     and provider.workspace_id = model.workspace_id
                    where route.tenant_id = ? and route.workspace_id = ? and route.id = ?
                      and route.name || '@' || route.version = ?
                      and route.route_capability = ? and route.status = 'PUBLISHED'
                      and model.enabled and provider.enabled
                    """, scope.tenantId(), scope.workspaceId(), component.modelRouteId(),
                    component.modelRouteReference(), requiredCapability).get(0, Integer.class);
            if (count == null || count != 1) {
                throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_ROUTE_INVALID");
            }
        }
    }

    private UUID findIdempotentReservation(
            WorkspaceScope scope, ExecutionReservationRequest request) {
        Set<UUID> reservationIds = new HashSet<>();
        for (ExecutionComponentRequest component : request.components()) {
            reservationIds.addAll(sql.fetch("""
                    select distinct reservation_id
                    from execution_reservation_component
                    where tenant_id = ? and workspace_id = ? and idempotency_identity = ?
                    """, scope.tenantId(), scope.workspaceId(), component.idempotencyIdentity())
                    .getValues("reservation_id", UUID.class));
        }
        if (reservationIds.isEmpty()) {
            return null;
        }
        if (reservationIds.size() != 1) {
            throw conflict("APVERO_EXECUTION_RESERVATION_IDEMPOTENCY_CONFLICT");
        }
        UUID reservationId = reservationIds.iterator().next();
        var reservation = sql.fetchOptional("""
                select subject_type, subject_id, actor_id, trace_id
                from execution_reservation
                where tenant_id = ? and workspace_id = ? and id = ?
                for update
                """, scope.tenantId(), scope.workspaceId(), reservationId)
                .orElseThrow(() -> conflict("APVERO_EXECUTION_RESERVATION_IDEMPOTENCY_CONFLICT"));
        List<ExecutionComponentPersistenceRecord> stored =
                components.listByReservation(scope, reservationId);
        Map<String, ExecutionComponentRequest> requested = request.components().stream()
                .collect(Collectors.toMap(ExecutionComponentRequest::idempotencyIdentity,
                        Function.identity()));
        boolean equal = request.subject().type().name()
                        .equals(reservation.get("subject_type", String.class))
                && request.subject().id().equals(reservation.get("subject_id", UUID.class))
                && request.actorId().equals(reservation.get("actor_id", String.class))
                && request.traceId().equals(reservation.get("trace_id", String.class))
                && stored.size() == requested.size()
                && stored.stream().allMatch(row -> equalsRequest(row,
                        requested.get(row.idempotencyIdentity())));
        if (!equal) {
            throw conflict("APVERO_EXECUTION_RESERVATION_IDEMPOTENCY_CONFLICT");
        }
        return reservationId;
    }

    private static boolean equalsRequest(
            ExecutionComponentPersistenceRecord stored, ExecutionComponentRequest requested) {
        return requested != null
                && requested.type().name().equals(stored.componentType())
                && requested.modelRouteId().equals(stored.modelRouteId())
                && requested.modelRouteReference().equals(stored.modelRouteReference())
                && requested.estimatedUnits() == stored.estimatedUnits()
                && requested.estimatedCostMicros() == stored.estimatedCostMicros()
                && requested.currency().equals(stored.currency());
    }

    private void evaluateComponentPolicies(
            WorkspaceScope scope, ExecutionReservationRequest request, OffsetDateTime now) {
        OffsetDateTime minute = now.withSecond(0).withNano(0);
        OffsetDateTime month = now.withDayOfMonth(1).toLocalDate()
                .atStartOfDay().atOffset(ZoneOffset.UTC);
        UUID applicationId = request.subject().type() == ExecutionSubjectType.APPLICATION_RUN
                ? request.subject().id() : null;
        for (BudgetPolicy policy : listBudgets(scope.workspaceId()).stream()
                .filter(BudgetPolicy::enabled).toList()) {
            long requestedCost = requestedCost(policy, request, applicationId);
            if (requestedCost < 0) {
                continue;
            }
            if (policy.requestsPerMinute() != null) {
                int count = sql.fetchOne("""
                        insert into rate_limit_counter(policy_id, window_started_at, request_count)
                        values (?, ?::timestamptz, 1)
                        on conflict (policy_id, window_started_at)
                        do update set request_count = rate_limit_counter.request_count + 1
                        returning request_count
                        """, policy.id(), minute).get("request_count", Integer.class);
                if (count > policy.requestsPerMinute()) {
                    deny(scope, request, "RATE_LIMIT_EXCEEDED");
                    throw new RateLimitExceededException();
                }
            }
            if (policy.monthlyCostLimitMicros() != null) {
                long consumed = consumedCost(scope, policy, month);
                long projected;
                try {
                    projected = Math.addExact(consumed, requestedCost);
                } catch (ArithmeticException exception) {
                    deny(scope, request, "BUDGET_EXCEEDED");
                    throw new BudgetExceededException();
                }
                if (projected > policy.monthlyCostLimitMicros()) {
                    deny(scope, request, "BUDGET_EXCEEDED");
                    throw new BudgetExceededException();
                }
            }
        }
    }

    private long requestedCost(
            BudgetPolicy policy, ExecutionReservationRequest request, UUID applicationId) {
        return switch (policy.scopeType()) {
            case WORKSPACE -> request.estimatedCostMicros();
            case APPLICATION -> applicationId != null && applicationId.equals(policy.scopeId())
                    ? request.estimatedCostMicros() : -1;
            case MODEL_ROUTE -> {
                long total = 0;
                boolean matched = false;
                try {
                    for (ExecutionComponentRequest component : request.components()) {
                        if (component.modelRouteId().equals(policy.scopeId())) {
                            matched = true;
                            total = Math.addExact(total, component.estimatedCostMicros());
                        }
                    }
                } catch (ArithmeticException exception) {
                    throw new IllegalArgumentException(
                            "APVERO_EXECUTION_COMPONENT_COST_OVERFLOW", exception);
                }
                yield matched ? total : -1;
            }
        };
    }

    private long consumedCost(
            WorkspaceScope scope, BudgetPolicy policy, OffsetDateTime month) {
        if (policy.scopeType() == BudgetScopeType.MODEL_ROUTE) {
            Long value = sql.fetchOne("""
                    select coalesce(sum(cost_micros), 0)
                    from (
                        select coalesce(component.actual_cost_micros,
                            component.estimated_cost_micros) as cost_micros
                        from execution_reservation_component component
                        where component.tenant_id = ? and component.workspace_id = ?
                          and component.model_route_id = ? and component.created_at >= ?::timestamptz
                        union all
                        select coalesce(reservation.actual_cost_micros,
                            reservation.estimated_cost_micros)
                        from execution_reservation reservation
                        where reservation.tenant_id = ? and reservation.workspace_id = ?
                          and reservation.model_route_id = ?
                          and reservation.created_at >= ?::timestamptz
                          and not exists (
                              select 1 from execution_reservation_component component
                              where component.reservation_id = reservation.id)
                    ) charged
                    """, scope.tenantId(), scope.workspaceId(), policy.scopeId(), month,
                    scope.tenantId(), scope.workspaceId(), policy.scopeId(), month)
                    .get(0, Long.class);
            return value == null ? 0 : value;
        }
        String applicationClause = policy.scopeType() == BudgetScopeType.APPLICATION
                ? " and application_id = ?" : "";
        var bindings = policy.scopeType() == BudgetScopeType.APPLICATION
                ? new Object[]{scope.tenantId(), scope.workspaceId(), month, policy.scopeId()}
                : new Object[]{scope.tenantId(), scope.workspaceId(), month};
        Long value = sql.fetchOne("""
                select coalesce(sum(coalesce(actual_cost_micros, estimated_cost_micros)), 0)
                from execution_reservation
                where tenant_id = ? and workspace_id = ? and created_at >= ?::timestamptz
                """ + applicationClause, bindings).get(0, Long.class);
        return value == null ? 0 : value;
    }

    private void deny(
            WorkspaceScope scope, ExecutionReservationRequest request, String reasonCode) {
        policyAudit.denied(scope.tenantId(), scope.workspaceId(), request.subject().id(),
                request.subject().type().name(), request.actorId(), request.traceId(), reasonCode);
    }

    private ExecutionAdmission admission(UUID reservationId, UUID workspaceId) {
        RetentionPolicy retention = get(workspaceId);
        return new ExecutionAdmission(
                reservationId, retention.retainPayloads(), retention.maskSensitiveFields());
    }

    private WorkspaceScope scopeForReservation(UUID reservationId) {
        UUID workspaceId = sql.fetchOptional("""
                select workspace_id from execution_reservation where id = ?
                """, reservationId)
                .map(record -> record.get("workspace_id", UUID.class))
                .orElseThrow(() -> conflict("APVERO_EXECUTION_COMPONENT_NOT_FOUND"));
        WorkspaceScope scope = workspaces.require(workspaceId);
        Integer count = sql.fetchOne("""
                select count(*) from execution_reservation
                where id = ? and tenant_id = ? and workspace_id = ?
                """, reservationId, scope.tenantId(), scope.workspaceId())
                .get(0, Integer.class);
        if (count == null || count != 1) {
            throw conflict("APVERO_EXECUTION_COMPONENT_NOT_FOUND");
        }
        return scope;
    }

    private static ExecutionComponentSnapshot snapshot(
            ExecutionComponentPersistenceRecord row) {
        return new ExecutionComponentSnapshot(
                row.reservationId(),
                ExecutionComponentType.valueOf(row.componentType()),
                row.modelRouteId(),
                row.modelRouteReference(),
                row.idempotencyIdentity(),
                row.estimatedUnits(),
                row.actualUnits(),
                row.estimatedCostMicros(),
                row.actualCostMicros(),
                row.currency(),
                row.usageQuality() == null
                        ? null
                        : ExecutionUsageQuality.valueOf(row.usageQuality()),
                ExecutionComponentState.valueOf(row.status()),
                row.providerRequestIdentity(),
                row.failureCode());
    }

    private void aggregateParent(WorkspaceScope scope, UUID reservationId) {
        List<ExecutionComponentPersistenceRecord> rows =
                components.listByReservation(scope, reservationId);
        if (rows.isEmpty()
                || rows.stream().anyMatch(row -> !"SUCCEEDED".equals(row.status())
                        && !"FAILED".equals(row.status()))) {
            return;
        }
        long actualCost = 0;
        try {
            for (ExecutionComponentPersistenceRecord row : rows) {
                actualCost = Math.addExact(actualCost,
                        Objects.requireNonNull(row.actualCostMicros()));
            }
        } catch (ArithmeticException exception) {
            throw conflict("APVERO_EXECUTION_COMPONENT_COST_OVERFLOW");
        }
        boolean succeeded = rows.stream().allMatch(row -> "SUCCEEDED".equals(row.status()));
        int changed = sql.execute("""
                update execution_reservation
                set actual_cost_micros = ?, status = ?, settled_at = ?::timestamptz
                where tenant_id = ? and workspace_id = ? and id = ? and status = 'RESERVED'
                """, actualCost, succeeded ? "SUCCEEDED" : "FAILED",
                OffsetDateTime.now(ZoneOffset.UTC), scope.tenantId(), scope.workspaceId(),
                reservationId);
        if (changed != 1) {
            var current = sql.fetchOne("""
                    select actual_cost_micros, status from execution_reservation
                    where tenant_id = ? and workspace_id = ? and id = ?
                    """, scope.tenantId(), scope.workspaceId(), reservationId);
            if (current == null
                    || !Long.valueOf(actualCost).equals(
                            current.get("actual_cost_micros", Long.class))
                    || !(succeeded ? "SUCCEEDED" : "FAILED")
                            .equals(current.get("status", String.class))) {
                throw conflict("APVERO_EXECUTION_RESERVATION_SETTLEMENT_CONFLICT");
            }
        }
    }

    private static IllegalStateException conflict(String code) {
        return new IllegalStateException(code);
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
