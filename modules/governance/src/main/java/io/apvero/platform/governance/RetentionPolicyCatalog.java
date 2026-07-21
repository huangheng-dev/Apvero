package io.apvero.platform.governance;

import java.util.UUID;

public interface RetentionPolicyCatalog {
    RetentionPolicy get(UUID workspaceId);

    RetentionPolicy update(UUID workspaceId, int runRetentionDays, int auditRetentionDays,
            boolean retainPayloads, boolean maskSensitiveFields);
}
