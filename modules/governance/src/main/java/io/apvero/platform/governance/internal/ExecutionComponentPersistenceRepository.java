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

    List<ExecutionComponentPersistenceRecord> listByReservation(
            WorkspaceScope scope, UUID reservationId);
}
