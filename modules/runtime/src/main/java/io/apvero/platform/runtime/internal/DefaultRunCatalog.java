package io.apvero.platform.runtime.internal;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleaseCatalog;
import io.apvero.platform.runtime.ExecuteRunCommand;
import io.apvero.platform.runtime.ProviderRequest;
import io.apvero.platform.runtime.ProviderResult;
import io.apvero.platform.runtime.RunCatalog;
import io.apvero.platform.runtime.RunRecord;
import io.apvero.platform.runtime.RuntimeProvider;
import io.apvero.platform.runtime.UsageSummary;
import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultRunCatalog implements RunCatalog {
    private final ApplicationCatalog applications;
    private final ReleaseCatalog releases;
    private final RuntimeProviderRegistry providers;
    private final RunRepository repository;

    public DefaultRunCatalog(
            ApplicationCatalog applications,
            ReleaseCatalog releases,
            RuntimeProviderRegistry providers,
            RunRepository repository) {
        this.applications = applications;
        this.releases = releases;
        this.providers = providers;
        this.repository = repository;
    }

    @Override
    public List<RunRecord> list(UUID workspaceId) {
        return repository.findAll(workspaceId);
    }

    @Override
    @Transactional
    public RunRecord execute(UUID workspaceId, UUID applicationId, ExecuteRunCommand command) {
        AiApplication application = applications.get(workspaceId, applicationId);
        ReleaseBundle release = releases.get(workspaceId, command.releaseId());
        if (!release.applicationId().equals(application.id())) {
            throw new IllegalArgumentException("The release does not belong to the requested application.");
        }
        if (release.purpose() == io.apvero.platform.release.ReleasePurpose.PREVIEW
                && release.expiresAt() != null && release.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException("The preview execution bundle has expired.");
        }
        String traceId = UUID.randomUUID().toString().replace("-", "");
        long started = System.nanoTime();
        RuntimeProvider provider = null;
        try {
            provider = providers.resolve(release);
            ProviderResult result = provider.execute(new ProviderRequest(release, command.input(), traceId));
            long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            return repository.insert(application, release, provider.id(), command.input(), result, latencyMs, traceId);
        } catch (RuntimeException exception) {
            long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            String category = exception instanceof IllegalArgumentException ? "INVALID_CONFIGURATION" : "PROVIDER_FAILURE";
            return repository.insertFailure(application, release, provider == null ? "unresolved" : provider.id(),
                    command.input(), latencyMs, traceId, category, "Provider execution failed.");
        }
    }

    @Override
    public UsageSummary usage(UUID workspaceId) {
        return repository.summarize(workspaceId);
    }
}
