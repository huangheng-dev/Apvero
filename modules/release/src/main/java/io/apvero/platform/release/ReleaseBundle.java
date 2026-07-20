package io.apvero.platform.release;

import tools.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ReleaseBundle(
        UUID id,
        UUID tenantId,
        UUID workspaceId,
        UUID applicationId,
        String version,
        String artifactDigest,
        JsonNode manifest,
        ReleaseStatus status,
        ReleasePurpose purpose,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) {}
