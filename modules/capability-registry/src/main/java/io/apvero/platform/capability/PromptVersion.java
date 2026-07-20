package io.apvero.platform.capability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PromptVersion(
        UUID id, UUID tenantId, UUID workspaceId, UUID promptAssetId, int version, String systemPrompt,
        List<String> variables, String status, OffsetDateTime createdAt) {}
