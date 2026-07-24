package io.apvero.platform.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.identity.WorkspaceScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Autowired ExecutionComponentPersistenceRepository repository;
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
