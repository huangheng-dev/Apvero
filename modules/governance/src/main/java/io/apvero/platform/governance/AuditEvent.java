package io.apvero.platform.governance;

import tools.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditEvent(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        OffsetDateTime occurredAt,
        String actorId,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String sourceIp,
        String traceId,
        JsonNode details) {}
