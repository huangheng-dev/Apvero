package io.apvero.platform.governance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface GovernanceMaintenance {
    List<RetentionTarget> retentionTargets();

    int purgeAuditBefore(UUID workspaceId, OffsetDateTime cutoff);

    int purgeRateCountersBefore(OffsetDateTime cutoff);

    int reconcileStaleReservationsBefore(OffsetDateTime cutoff);
}
