package io.apvero.platform.governance;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ExecutionReservationRequest(
        UUID workspaceId,
        ExecutionSubject subject,
        String actorId,
        String traceId,
        List<ExecutionComponentRequest> components) {

    public ExecutionReservationRequest {
        Objects.requireNonNull(workspaceId, "APVERO_WORKSPACE_ID_REQUIRED");
        Objects.requireNonNull(subject, "APVERO_EXECUTION_SUBJECT_REQUIRED");
        if (actorId == null || actorId.isBlank() || actorId.length() > 160) {
            throw new IllegalArgumentException("APVERO_EXECUTION_ACTOR_INVALID");
        }
        if (traceId == null || traceId.isBlank() || traceId.length() > 200) {
            throw new IllegalArgumentException("APVERO_EXECUTION_TRACE_INVALID");
        }
        components = List.copyOf(Objects.requireNonNull(
                components, "APVERO_EXECUTION_COMPONENTS_REQUIRED"));
        if (components.isEmpty()) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENTS_EMPTY");
        }
        Set<String> identities = new HashSet<>();
        for (ExecutionComponentRequest component : components) {
            if (!identities.add(component.idempotencyIdentity())) {
                throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_DUPLICATE");
            }
            if (!allows(subject.type(), component.type())) {
                throw new IllegalArgumentException("APVERO_EXECUTION_SUBJECT_COMPONENT_INVALID");
            }
        }
        actorId = actorId.trim();
        traceId = traceId.trim();
    }

    public long estimatedCostMicros() {
        long total = 0;
        try {
            for (ExecutionComponentRequest component : components) {
                total = Math.addExact(total, component.estimatedCostMicros());
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("APVERO_EXECUTION_COMPONENT_COST_OVERFLOW", exception);
        }
        return total;
    }

    private static boolean allows(ExecutionSubjectType subject, ExecutionComponentType component) {
        return switch (subject) {
            case APPLICATION_RUN -> component == ExecutionComponentType.CHAT_GENERATION
                    || component == ExecutionComponentType.EMBEDDING_QUERY;
            case KNOWLEDGE_INGESTION -> component == ExecutionComponentType.EMBEDDING_INDEX;
            case KNOWLEDGE_QUERY -> component == ExecutionComponentType.EMBEDDING_QUERY;
        };
    }
}
