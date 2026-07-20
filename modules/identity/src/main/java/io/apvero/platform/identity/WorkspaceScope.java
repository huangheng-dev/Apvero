package io.apvero.platform.identity;

import java.util.UUID;

public record WorkspaceScope(UUID tenantId, UUID workspaceId) {}
