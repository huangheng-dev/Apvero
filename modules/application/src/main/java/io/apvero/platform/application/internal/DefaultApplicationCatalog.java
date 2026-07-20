package io.apvero.platform.application.internal;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.application.ApplicationNotFoundException;
import io.apvero.platform.application.BindApplicationDraftCommand;
import io.apvero.platform.application.CreateApplicationCommand;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultApplicationCatalog implements ApplicationCatalog {
    private final ApplicationRepository repository;

    public DefaultApplicationCatalog(ApplicationRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<AiApplication> list(UUID workspaceId) {
        return repository.findAll(workspaceId);
    }

    @Override
    public AiApplication get(UUID workspaceId, UUID applicationId) {
        return repository.findById(workspaceId, applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }

    @Override
    @Transactional
    public AiApplication create(UUID workspaceId, CreateApplicationCommand command) {
        return repository.insert(workspaceId, command);
    }

    @Override
    @Transactional
    public AiApplication bindDraft(UUID workspaceId, UUID applicationId, BindApplicationDraftCommand command) {
        get(workspaceId, applicationId);
        return repository.bindDraft(workspaceId, applicationId, command.modelRouteId(), command.promptVersionId());
    }
}
