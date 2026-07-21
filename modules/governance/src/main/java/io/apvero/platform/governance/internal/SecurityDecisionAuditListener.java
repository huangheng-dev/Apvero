package io.apvero.platform.governance.internal;

import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.identity.SecurityDecisionEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class SecurityDecisionAuditListener {
    private final AuditEventCatalog audit;

    SecurityDecisionAuditListener(AuditEventCatalog audit) {
        this.audit = audit;
    }

    @EventListener
    void onDecision(SecurityDecisionEvent event) {
        audit.append(event.workspaceId(), event.actorId(), event.reasonCode() + " " + event.action(),
                "workspace-access", event.resourceId(), "DENIED", event.sourceIp(), null);
    }
}
