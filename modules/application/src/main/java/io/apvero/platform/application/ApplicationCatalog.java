package io.apvero.platform.application;

import java.util.List;
import java.util.UUID;

public interface ApplicationCatalog {
    List<AiApplication> list(UUID workspaceId);

    AiApplication get(UUID workspaceId, UUID applicationId);

    AiApplication create(UUID workspaceId, CreateApplicationCommand command);

    AiApplication bindDraft(UUID workspaceId, UUID applicationId, BindApplicationDraftCommand command);
}
