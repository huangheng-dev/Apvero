package io.apvero.platform.release.internal;

import tools.jackson.databind.JsonNode;
import io.apvero.platform.application.AiApplication;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleasePurpose;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ReleaseRepository {
    List<ReleaseBundle> findAll(UUID workspaceId, UUID applicationId);

    Optional<ReleaseBundle> findById(UUID workspaceId, UUID releaseId);

    ReleaseBundle insert(AiApplication application, String version, String digest, JsonNode manifest,
            ReleasePurpose purpose, OffsetDateTime expiresAt);
}
