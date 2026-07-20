package io.apvero.platform.application.api;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.application.BindApplicationDraftCommand;
import io.apvero.platform.application.CreateApplicationCommand;
import io.apvero.platform.application.RuntimeMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/applications")
final class ApplicationController {
    private final ApplicationCatalog applications;

    ApplicationController(ApplicationCatalog applications) {
        this.applications = applications;
    }

    @GetMapping
    List<AiApplication> list(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return applications.list(workspaceId);
    }

    @PostMapping
    ResponseEntity<AiApplication> create(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @Valid @RequestBody CreateApplicationRequest request) {
        AiApplication created = applications.create(workspaceId, new CreateApplicationCommand(
                request.slug(), request.name(), request.description(), request.runtimeMode()));
        return ResponseEntity.created(URI.create("/api/v1/applications/" + created.id())).body(created);
    }

    @PatchMapping("/{applicationId}/draft")
    AiApplication bindDraft(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID applicationId,
            @Valid @RequestBody BindDraftRequest request) {
        return applications.bindDraft(workspaceId, applicationId,
                new BindApplicationDraftCommand(request.modelRouteId(), request.promptVersionId()));
    }

    record CreateApplicationRequest(
            @NotBlank @Size(max = 80)
                    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "must be a lowercase slug")
                    String slug,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 2000) String description,
            @NotNull RuntimeMode runtimeMode) {}

    record BindDraftRequest(@NotNull UUID modelRouteId, @NotNull UUID promptVersionId) {}
}
