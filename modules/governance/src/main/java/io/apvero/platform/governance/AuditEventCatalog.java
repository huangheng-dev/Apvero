package io.apvero.platform.governance;

import java.util.List;
import java.util.UUID;

public interface AuditEventCatalog {
    List<AuditEvent> listAuditEvents(UUID workspaceId);

    void append(UUID workspaceId, String actorId, String action, String resourceType,
            String resourceId, String outcome, String sourceIp, String traceId);
}
