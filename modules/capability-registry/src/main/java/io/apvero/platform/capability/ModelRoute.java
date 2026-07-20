package io.apvero.platform.capability;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ModelRoute(
        UUID id, UUID tenantId, UUID workspaceId, String name, long version, UUID modelId, String status,
        int timeoutMs, int maxOutputTokens, BigDecimal temperature, OffsetDateTime createdAt) {
    public String reference() {
        return name + "@" + version;
    }
}
