package io.apvero.platform.release.api;

import tools.jackson.databind.JsonNode;
import io.apvero.platform.release.CreateReleaseCommand;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleaseCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/applications/{applicationId}/releases")
final class ReleaseController {
    private final ReleaseCatalog releases;

    ReleaseController(ReleaseCatalog releases) {
        this.releases = releases;
    }

    @GetMapping
    List<ReleaseBundle> list(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID applicationId) {
        return releases.list(workspaceId, applicationId);
    }

    @PostMapping
    ResponseEntity<ReleaseBundle> create(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID applicationId,
            @Valid @RequestBody CreateReleaseRequest request) {
        ReleaseBundle release = releases.create(
                workspaceId, applicationId, new CreateReleaseCommand(request.version(), request.manifest()));
        return ResponseEntity.created(URI.create("/api/v1/releases/" + release.id())).body(release);
    }

    record CreateReleaseRequest(
            @NotBlank @Size(max = 64)
                    @Pattern(regexp = "^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?$", message = "must be a semantic version")
                    String version,
            JsonNode manifest) {}
}
