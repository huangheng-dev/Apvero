package io.apvero.platform;

import io.apvero.platform.governance.GovernanceMaintenance;
import io.apvero.platform.runtime.RunCatalog;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RetentionMaintenance {
    private final GovernanceMaintenance governance;
    private final RunCatalog runs;

    RetentionMaintenance(GovernanceMaintenance governance, RunCatalog runs) {
        this.governance = governance;
        this.runs = runs;
    }

    @Scheduled(cron = "${apvero.retention.cron:0 17 2 * * *}", zone = "UTC")
    void enforce() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (var target : governance.retentionTargets()) {
            runs.purgeBefore(target.workspaceId(), now.minusDays(target.runRetentionDays()));
            governance.purgeAuditBefore(target.workspaceId(), now.minusDays(target.auditRetentionDays()));
        }
        governance.purgeRateCountersBefore(now.minusDays(2));
        governance.reconcileStaleReservationsBefore(now.minusHours(1));
    }
}
