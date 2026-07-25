package io.apvero.platform.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.governance.ExecutionAdmission;
import io.apvero.platform.governance.BudgetExceededException;
import io.apvero.platform.governance.ExecutionComponentDispatch;
import io.apvero.platform.governance.ExecutionComponentReconciliation;
import io.apvero.platform.governance.ExecutionComponentRequest;
import io.apvero.platform.governance.ExecutionComponentSettlement;
import io.apvero.platform.governance.ExecutionComponentType;
import io.apvero.platform.governance.ExecutionGovernance;
import io.apvero.platform.governance.ExecutionReservationRequest;
import io.apvero.platform.governance.ExecutionSubject;
import io.apvero.platform.governance.ExecutionUsageQuality;
import io.apvero.platform.governance.GovernanceMaintenance;
import io.apvero.platform.identity.WorkspaceScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
class P22bExecutionComponentPersistenceIntegrationTest {
    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID APPLICATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000001001");
    private static final UUID ROUTE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000003201");

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p22b_governance_test")
            .withUsername("apvero")
            .withPassword("apvero");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String externalUrl = System.getenv("APVERO_TEST_DB_URL");
        if (externalUrl == null || externalUrl.isBlank()) {
            POSTGRES.start();
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("APVERO_TEST_DB_USER", "apvero"));
            registry.add("spring.datasource.password",
                    () -> System.getenv().getOrDefault("APVERO_TEST_DB_PASSWORD", "apvero"));
        }
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Autowired ExecutionComponentPersistenceRepository repository;
    @Autowired ExecutionGovernance governance;
    @Autowired GovernanceMaintenance maintenance;
    @Autowired JdbcTemplate sql;

    @Test
    void componentLedgerIsScopedIdempotentAndOnlyAllowsForwardTransitions() {
        WorkspaceScope scope = new WorkspaceScope(TENANT_ID, WORKSPACE_ID);
        WorkspaceScope otherScope = createScope();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID reservationId = UUID.randomUUID();
        sql.update("""
                insert into execution_reservation(
                    id, tenant_id, workspace_id, application_id, subject_type, subject_id,
                    model_route_id, actor_id, trace_id, estimated_cost_micros, status, created_at)
                values (?, ?, ?, ?, 'APPLICATION_RUN', ?, ?, 'test', ?, 10, 'RESERVED', ?)
                """, reservationId, TENANT_ID, WORKSPACE_ID, APPLICATION_ID, APPLICATION_ID,
                ROUTE_ID, "component-" + reservationId, now);

        ExecutionComponentPersistenceRecord reserved = repository.insert(
                scope, new ExecutionComponentPersistenceRecord(
                        UUID.randomUUID(), TENANT_ID, WORKSPACE_ID, reservationId,
                        "CHAT_GENERATION", ROUTE_ID, "local-deterministic@1", "chat-0",
                        10, null, null, 10, null, "USD", "RESERVED",
                        null, null, null, null, now, now));
        assertThat(repository.find(scope, reserved.id())).contains(reserved);
        assertThat(repository.listByReservation(scope, reservationId)).containsExactly(reserved);
        assertThat(repository.find(otherScope, reserved.id())).isEmpty();
        assertThat(repository.listByReservation(otherScope, reservationId)).isEmpty();

        assertThatThrownBy(() -> repository.insert(
                otherScope, reserved))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_GOVERNANCE_SCOPE_MISMATCH");
        assertThatThrownBy(() -> repository.insert(
                scope, new ExecutionComponentPersistenceRecord(
                        UUID.randomUUID(), TENANT_ID, WORKSPACE_ID, reservationId,
                        "CHAT_GENERATION", ROUTE_ID, "local-deterministic@1", "chat-0",
                        10, null, null, 10, null, "USD", "RESERVED",
                        null, null, null, null, now, now)))
                .isInstanceOf(DataAccessException.class);

        OffsetDateTime dispatchedAt = now.plusSeconds(1);
        assertThat(sql.update("""
                update execution_reservation_component
                set status = 'DISPATCHED', provider_request_identity = 'request-safe',
                    dispatched_at = ?, updated_at = ?
                where id = ?
                """, dispatchedAt, dispatchedAt, reserved.id())).isEqualTo(1);
        OffsetDateTime settledAt = now.plusSeconds(2);
        assertThat(sql.update("""
                update execution_reservation_component
                set status = 'SUCCEEDED', actual_units = 9, usage_quality = 'ACTUAL',
                    actual_cost_micros = 9, settled_at = ?, updated_at = ?
                where id = ?
                """, settledAt, settledAt, reserved.id())).isEqualTo(1);
        assertThat(repository.find(scope, reserved.id()))
                .get()
                .extracting(
                        ExecutionComponentPersistenceRecord::status,
                        ExecutionComponentPersistenceRecord::actualUnits,
                        ExecutionComponentPersistenceRecord::actualCostMicros)
                .containsExactly("SUCCEEDED", 9L, 9L);

        assertThatThrownBy(() -> sql.update("""
                update execution_reservation_component
                set provider_request_identity = 'changed', updated_at = now()
                where id = ?
                """, reserved.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("settled execution_reservation_component is immutable");
        assertThatThrownBy(() -> sql.update(
                "delete from execution_reservation_component where id = ?", reserved.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("execution_reservation_component is durable");
        assertThatThrownBy(() -> sql.update("""
                insert into execution_reservation_component(
                    id, tenant_id, workspace_id, reservation_id, component_type,
                    model_route_id, model_route_reference, idempotency_identity,
                    estimated_units, estimated_cost_micros, currency, status,
                    created_at, updated_at)
                values (?, ?, ?, ?, 'CHAT_GENERATION', ?, 'local-deterministic@1',
                    'scope-attack', 0, 0, 'USD', 'RESERVED', now(), now())
                """, UUID.randomUUID(), otherScope.tenantId(), otherScope.workspaceId(),
                reservationId, ROUTE_ID))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void governanceClosesAdmissionDispatchSettlementAndReconciliationLifecycle() {
        UUID embeddingRouteId = createEmbeddingRoute("governance-lifecycle");
        UUID subjectId = UUID.randomUUID();
        ExecutionReservationRequest request = new ExecutionReservationRequest(
                WORKSPACE_ID,
                ExecutionSubject.knowledgeIngestion(subjectId),
                "p2.2c-test",
                "p22c-" + UUID.randomUUID(),
                List.of(
                        embeddingComponent(
                                embeddingRouteId, "governance-lifecycle@1", "batch-0", 11),
                        embeddingComponent(
                                embeddingRouteId, "governance-lifecycle@1", "batch-1", 13)));

        ExecutionAdmission first = governance.admit(request);
        ExecutionAdmission repeated = governance.admit(request);

        assertThat(repeated).isEqualTo(first);
        assertThat(sql.queryForObject("""
                select count(*) from execution_reservation
                where subject_type = 'KNOWLEDGE_INGESTION' and subject_id = ?
                """, Integer.class, subjectId)).isEqualTo(1);
        assertThat(repository.listByReservation(
                new WorkspaceScope(TENANT_ID, WORKSPACE_ID), first.reservationId()))
                .hasSize(2)
                .allMatch(row -> "RESERVED".equals(row.status()));

        ExecutionReservationRequest conflict = new ExecutionReservationRequest(
                request.workspaceId(), request.subject(), request.actorId(), request.traceId(),
                List.of(
                        embeddingComponent(
                                embeddingRouteId, "governance-lifecycle@1", "batch-0", 12),
                        embeddingComponent(
                                embeddingRouteId, "governance-lifecycle@1", "batch-1", 13)));
        assertThatThrownBy(() -> governance.admit(conflict))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_EXECUTION_RESERVATION_IDEMPOTENCY_CONFLICT");

        governance.markDispatched(new ExecutionComponentDispatch(
                first.reservationId(), "batch-0", null));
        governance.markDispatched(new ExecutionComponentDispatch(
                first.reservationId(), "batch-0", "provider-request-0"));
        governance.markDispatched(new ExecutionComponentDispatch(
                first.reservationId(), "batch-0", "provider-request-0"));
        assertThatThrownBy(() -> governance.markDispatched(new ExecutionComponentDispatch(
                first.reservationId(), "batch-0", "different-provider-request")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_EXECUTION_PROVIDER_IDENTITY_CONFLICT");

        ExecutionComponentSettlement firstSettlement = new ExecutionComponentSettlement(
                first.reservationId(), "batch-0", 90, 7, "USD",
                ExecutionUsageQuality.ACTUAL, true, null);
        governance.settle(firstSettlement);
        governance.settle(firstSettlement);
        assertThatThrownBy(() -> governance.settle(new ExecutionComponentSettlement(
                first.reservationId(), "batch-0", 91, 7, "USD",
                ExecutionUsageQuality.ACTUAL, true, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APVERO_EXECUTION_COMPONENT_SETTLEMENT_CONFLICT");
        assertThat(parentStatus(first.reservationId())).isEqualTo("RESERVED");

        governance.markDispatched(new ExecutionComponentDispatch(
                first.reservationId(), "batch-1", "provider-request-1"));
        ExecutionComponentSettlement secondSettlement = new ExecutionComponentSettlement(
                first.reservationId(), "batch-1", 100, 5, "USD",
                ExecutionUsageQuality.ESTIMATED, false, "APVERO_PROVIDER_REJECTED");
        governance.settle(secondSettlement);
        governance.settle(secondSettlement);

        assertThat(parentStatus(first.reservationId())).isEqualTo("FAILED");
        assertThat(sql.queryForObject("""
                select actual_cost_micros from execution_reservation where id = ?
                """, Long.class, first.reservationId())).isEqualTo(12L);

        ExecutionReservationRequest ambiguousRequest = new ExecutionReservationRequest(
                WORKSPACE_ID,
                ExecutionSubject.knowledgeIngestion(UUID.randomUUID()),
                "p2.2c-test",
                "p22c-ambiguous-" + UUID.randomUUID(),
                List.of(embeddingComponent(
                        embeddingRouteId, "governance-lifecycle@1", "ambiguous-0", 3)));
        ExecutionAdmission ambiguous = governance.admit(ambiguousRequest);
        governance.markDispatched(new ExecutionComponentDispatch(
                ambiguous.reservationId(), "ambiguous-0", null));
        ExecutionComponentReconciliation reconciliation =
                new ExecutionComponentReconciliation(
                        ambiguous.reservationId(), "ambiguous-0",
                        "APVERO_EMBEDDING_OUTCOME_AMBIGUOUS");
        governance.requireReconciliation(reconciliation);
        governance.requireReconciliation(reconciliation);

        ExecutionComponentPersistenceRecord ambiguousComponent =
                repository.findByIdentity(
                        new WorkspaceScope(TENANT_ID, WORKSPACE_ID),
                        ambiguous.reservationId(), "ambiguous-0")
                        .orElseThrow();
        assertThat(ambiguousComponent.status()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(ambiguousComponent.actualCostMicros()).isNull();
        assertThat(parentStatus(ambiguous.reservationId())).isEqualTo("RESERVED");
    }

    @Test
    void staleMaintenanceNeverSettlesComponentWorkAtZero() {
        UUID embeddingRouteId = createEmbeddingRoute("stale-safety");
        ExecutionAdmission reserved = governance.admit(new ExecutionReservationRequest(
                WORKSPACE_ID,
                ExecutionSubject.knowledgeIngestion(UUID.randomUUID()),
                "p2.2c-test",
                "p22c-stale-" + UUID.randomUUID(),
                List.of(embeddingComponent(
                        embeddingRouteId, "stale-safety@1", "stale-0", 2))));
        sql.update("""
                update execution_reservation
                set created_at = now() - interval '2 hours'
                where id = ?
                """, reserved.reservationId());

        assertThat(maintenance.reconcileStaleReservationsBefore(
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1))).isZero();
        assertThat(parentStatus(reserved.reservationId())).isEqualTo("RESERVED");
        assertThat(repository.findByIdentity(
                new WorkspaceScope(TENANT_ID, WORKSPACE_ID),
                reserved.reservationId(), "stale-0").orElseThrow().status())
                .isEqualTo("RESERVED");

        ExecutionAdmission dispatched = governance.admit(new ExecutionReservationRequest(
                WORKSPACE_ID,
                ExecutionSubject.knowledgeIngestion(UUID.randomUUID()),
                "p2.2c-test",
                "p22c-stale-dispatched-" + UUID.randomUUID(),
                List.of(embeddingComponent(
                        embeddingRouteId, "stale-safety@1", "stale-dispatched-0", 2))));
        governance.markDispatched(new ExecutionComponentDispatch(
                dispatched.reservationId(), "stale-dispatched-0", null));

        assertThat(maintenance.reconcileStaleReservationsBefore(
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1))).isEqualTo(1);
        ExecutionComponentPersistenceRecord staleDispatched = repository.findByIdentity(
                new WorkspaceScope(TENANT_ID, WORKSPACE_ID),
                dispatched.reservationId(), "stale-dispatched-0").orElseThrow();
        assertThat(staleDispatched.status()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(staleDispatched.failureCode()).isEqualTo("APVERO_EXECUTION_STALE_DISPATCH");
        assertThat(staleDispatched.actualCostMicros()).isNull();
        assertThat(parentStatus(dispatched.reservationId())).isEqualTo("RESERVED");
    }

    @Test
    void knowledgeAdmissionSkipsApplicationPolicyAndEnforcesExactRoutePolicy() {
        UUID allowedRoute = createEmbeddingRoute("policy-allowed");
        sql.update("""
                insert into budget_policy(
                    id, tenant_id, workspace_id, name, scope_type, scope_id,
                    monthly_cost_limit_micros, enabled, created_at, updated_at)
                values (?, ?, ?, ?, 'APPLICATION', ?, 0, true, now(), now())
                """, UUID.randomUUID(), TENANT_ID, WORKSPACE_ID,
                "unrelated-application-" + UUID.randomUUID(), APPLICATION_ID);
        ExecutionAdmission allowed = governance.admit(new ExecutionReservationRequest(
                WORKSPACE_ID,
                ExecutionSubject.knowledgeIngestion(UUID.randomUUID()),
                "p2.2c-test",
                "p22c-application-policy-" + UUID.randomUUID(),
                List.of(embeddingComponent(
                        allowedRoute, "policy-allowed@1",
                        "application-policy-skip-" + UUID.randomUUID(), 5))));
        assertThat(parentStatus(allowed.reservationId())).isEqualTo("RESERVED");

        UUID deniedRoute = createEmbeddingRoute("policy-denied");
        sql.update("""
                insert into budget_policy(
                    id, tenant_id, workspace_id, name, scope_type, scope_id,
                    monthly_cost_limit_micros, enabled, created_at, updated_at)
                values (?, ?, ?, ?, 'MODEL_ROUTE', ?, 0, true, now(), now())
                """, UUID.randomUUID(), TENANT_ID, WORKSPACE_ID,
                "exact-route-" + UUID.randomUUID(), deniedRoute);
        ExecutionReservationRequest denied = new ExecutionReservationRequest(
                WORKSPACE_ID,
                ExecutionSubject.knowledgeIngestion(UUID.randomUUID()),
                "p2.2c-test",
                "p22c-route-policy-" + UUID.randomUUID(),
                List.of(embeddingComponent(
                        deniedRoute, "policy-denied@1",
                        "route-policy-deny-" + UUID.randomUUID(), 1)));

        assertThatThrownBy(() -> governance.admit(denied))
                .isInstanceOf(BudgetExceededException.class);
        assertThat(sql.queryForObject("""
                select count(*) from execution_reservation where trace_id = ?
                """, Integer.class, denied.traceId())).isZero();
    }

    private ExecutionComponentRequest embeddingComponent(
            UUID routeId, String routeReference, String identity, long estimatedCost) {
        return new ExecutionComponentRequest(
                ExecutionComponentType.EMBEDDING_INDEX,
                routeId,
                routeReference,
                identity,
                100,
                estimatedCost,
                "USD");
    }

    private UUID createEmbeddingRoute(String name) {
        UUID modelId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        sql.update("""
                insert into model_definition(
                    id, tenant_id, workspace_id, provider_id, model_key, name, capabilities,
                    input_cost_micros_per_million, output_cost_micros_per_million,
                    enabled, created_at, updated_at)
                values (?, ?, ?, '00000000-0000-0000-0000-000000003001', ?, ?,
                    '["EMBEDDING"]'::jsonb, 0, 0, true, now(), now())
                """, modelId, TENANT_ID, WORKSPACE_ID, name, name);
        sql.update("""
                insert into model_route(
                    id, tenant_id, workspace_id, name, version, model_id, route_capability,
                    status, timeout_ms, max_output_tokens, temperature, embedding_dimension,
                    embedding_maximum_input_tokens, embedding_maximum_batch_size,
                    embedding_normalization, created_at)
                values (?, ?, ?, ?, 1, ?, 'EMBEDDING', 'PUBLISHED', 30000,
                    null, null, 256, 8192, 64, 'L2', now())
                """, routeId, TENANT_ID, WORKSPACE_ID, name, modelId);
        return routeId;
    }

    private String parentStatus(UUID reservationId) {
        return sql.queryForObject(
                "select status from execution_reservation where id = ?",
                String.class, reservationId);
    }

    private WorkspaceScope createScope() {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String suffix = tenantId.toString().replace("-", "").substring(0, 12);
        sql.update("insert into tenant(id, slug, name, created_at) values (?, ?, 'Other', now())",
                tenantId, "t-" + suffix);
        sql.update("""
                insert into workspace(id, tenant_id, slug, name, created_at)
                values (?, ?, ?, 'Other', now())
                """, workspaceId, tenantId, "w-" + suffix);
        return new WorkspaceScope(tenantId, workspaceId);
    }
}
