package io.apvero.platform.governance;

import java.util.UUID;

public record RetentionTarget(UUID workspaceId, int runRetentionDays, int auditRetentionDays) {}
