package io.apvero.platform.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionGovernanceCompatibilityTest {

    @Test
    void newApplicationRunShapeAdaptsToTheExistingP1Admission() {
        CapturingGovernance governance = new CapturingGovernance();
        UUID workspaceId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        ExecutionReservationRequest request = new ExecutionReservationRequest(
                workspaceId,
                ExecutionSubject.applicationRun(applicationId),
                "actor",
                "trace",
                List.of(new ExecutionComponentRequest(
                        ExecutionComponentType.CHAT_GENERATION,
                        routeId,
                        "default-chat@1",
                        "run-1:chat",
                        100,
                        23,
                        "USD")));

        ExecutionAdmission admission = governance.admit(request);

        assertThat(admission.reservationId()).isEqualTo(governance.admission.reservationId());
        assertThat(governance.workspaceId).isEqualTo(workspaceId);
        assertThat(governance.applicationId).isEqualTo(applicationId);
        assertThat(governance.routeId).isEqualTo(routeId);
        assertThat(governance.estimatedCostMicros).isEqualTo(23);
    }

    @Test
    void knowledgeComponentsRemainExplicitlyDisabledUntilPersistenceExists() {
        CapturingGovernance governance = new CapturingGovernance();
        ExecutionReservationRequest request = new ExecutionReservationRequest(
                UUID.randomUUID(),
                ExecutionSubject.knowledgeIngestion(UUID.randomUUID()),
                "actor",
                "trace",
                List.of(new ExecutionComponentRequest(
                        ExecutionComponentType.EMBEDDING_INDEX,
                        UUID.randomUUID(),
                        "quick-start-embedding@1",
                        "build-1:batch-1",
                        100,
                        0,
                        "USD")));

        assertThatThrownBy(() -> governance.admit(request))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("APVERO_GOVERNANCE_COMPONENT_RESERVATION_DISABLED");
        assertThatThrownBy(() -> governance.markDispatched(new ExecutionComponentDispatch(
                UUID.randomUUID(), "build-1:batch-1", null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("APVERO_GOVERNANCE_COMPONENT_DISPATCH_DISABLED");
    }

    @Test
    void duplicateComponentIdentitiesAndInvalidOutcomesFailClosed() {
        ExecutionComponentRequest component = new ExecutionComponentRequest(
                ExecutionComponentType.EMBEDDING_QUERY,
                UUID.randomUUID(),
                "quick-start-embedding@1",
                "query-1:embedding",
                10,
                0,
                "USD");
        assertThatThrownBy(() -> new ExecutionReservationRequest(
                UUID.randomUUID(),
                ExecutionSubject.knowledgeQuery(UUID.randomUUID()),
                "actor",
                "trace",
                List.of(component, component)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EXECUTION_COMPONENT_DUPLICATE");
        assertThatThrownBy(() -> new ExecutionComponentSettlement(
                UUID.randomUUID(), "query-1:embedding", 10, 0, "USD", true,
                "APVERO_PROVIDER_FAILED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EXECUTION_COMPONENT_OUTCOME_INVALID");
        assertThatThrownBy(() -> new ExecutionReservationRequest(
                UUID.randomUUID(),
                ExecutionSubject.knowledgeIngestion(UUID.randomUUID()),
                "actor",
                "trace",
                List.of(new ExecutionComponentRequest(
                        ExecutionComponentType.CHAT_GENERATION,
                        UUID.randomUUID(),
                        "default-chat@1",
                        "invalid-subject-component",
                        10,
                        0,
                        "USD"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EXECUTION_SUBJECT_COMPONENT_INVALID");

        ExecutionReservationRequest overflow = new ExecutionReservationRequest(
                UUID.randomUUID(),
                ExecutionSubject.applicationRun(UUID.randomUUID()),
                "actor",
                "trace",
                List.of(
                        new ExecutionComponentRequest(
                                ExecutionComponentType.EMBEDDING_QUERY,
                                UUID.randomUUID(),
                                "quick-start-embedding@1",
                                "run-1:query",
                                1,
                                Long.MAX_VALUE,
                                "USD"),
                        new ExecutionComponentRequest(
                                ExecutionComponentType.CHAT_GENERATION,
                                UUID.randomUUID(),
                                "default-chat@1",
                                "run-1:chat",
                                1,
                                1,
                                "USD")));
        assertThatThrownBy(overflow::estimatedCostMicros)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EXECUTION_COMPONENT_COST_OVERFLOW");
    }

    private static final class CapturingGovernance implements ExecutionGovernance {
        private final ExecutionAdmission admission =
                new ExecutionAdmission(UUID.randomUUID(), true, true);
        private UUID workspaceId;
        private UUID applicationId;
        private UUID routeId;
        private long estimatedCostMicros;

        @Override
        public ExecutionAdmission admit(UUID workspaceId, UUID applicationId, UUID modelRouteId,
                String actorId, String traceId, long estimatedCostMicros) {
            this.workspaceId = workspaceId;
            this.applicationId = applicationId;
            this.routeId = modelRouteId;
            this.estimatedCostMicros = estimatedCostMicros;
            return admission;
        }

        @Override
        public void settle(UUID reservationId, long actualCostMicros, boolean succeeded) {
        }
    }
}
