package io.apvero.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.governance.GovernanceMaintenance;
import io.apvero.platform.governance.RetentionTarget;
import io.apvero.platform.runtime.RunCatalog;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetentionMaintenanceTest {
    @Test
    void appliesBothRunAndAuditRetentionForEveryPolicy() {
        UUID workspaceId = UUID.randomUUID();
        GovernanceMaintenance governance = mock(GovernanceMaintenance.class);
        RunCatalog runs = mock(RunCatalog.class);
        when(governance.retentionTargets()).thenReturn(List.of(new RetentionTarget(workspaceId, 30, 365)));

        new RetentionMaintenance(governance, runs).enforce();

        verify(runs).purgeBefore(eq(workspaceId), any());
        verify(governance).purgeAuditBefore(eq(workspaceId), any());
        verify(governance).purgeRateCountersBefore(any());
        verify(governance).reconcileStaleReservationsBefore(any());
    }
}
