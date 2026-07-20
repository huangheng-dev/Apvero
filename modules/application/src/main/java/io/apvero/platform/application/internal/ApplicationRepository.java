package io.apvero.platform.application.internal;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.CreateApplicationCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ApplicationRepository {
    List<AiApplication> findAll(UUID workspaceId);

    Optional<AiApplication> findById(UUID workspaceId, UUID applicationId);

    AiApplication insert(UUID workspaceId, CreateApplicationCommand command);

    AiApplication bindDraft(UUID workspaceId, UUID applicationId, UUID modelRouteId, UUID promptVersionId);
}
