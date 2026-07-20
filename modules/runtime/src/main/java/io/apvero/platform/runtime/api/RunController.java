package io.apvero.platform.runtime.api;

import tools.jackson.databind.JsonNode;
import io.apvero.platform.runtime.ExecuteRunCommand;
import io.apvero.platform.runtime.RunCatalog;
import io.apvero.platform.runtime.RunRecord;
import io.apvero.platform.runtime.UsageSummary;
import io.apvero.platform.release.ReleaseCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/v1")
final class RunController {
    private final RunCatalog runs;
    private final ReleaseCatalog releases;

    RunController(RunCatalog runs, ReleaseCatalog releases) {
        this.runs = runs;
        this.releases = releases;
    }

    @GetMapping("/runs")
    List<RunRecord> list(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return runs.list(workspaceId);
    }

    @PostMapping("/applications/{applicationId}/runs")
    ResponseEntity<RunRecord> execute(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID applicationId,
            @Valid @RequestBody ExecuteRunRequest request) {
        RunRecord run = runs.execute(workspaceId, applicationId, new ExecuteRunCommand(request.releaseId(), request.input()));
        return ResponseEntity.ok(run);
    }

    @PostMapping("/applications/{applicationId}/preview-runs")
    ResponseEntity<RunRecord> preview(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID applicationId,
            @Valid @RequestBody PreviewRunRequest request) {
        var preview = releases.createPreview(workspaceId, applicationId);
        RunRecord run = runs.execute(workspaceId, applicationId, new ExecuteRunCommand(preview.id(), request.input()));
        return ResponseEntity.ok(run);
    }

    @GetMapping("/usage")
    UsageSummary usage(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return runs.usage(workspaceId);
    }

    record ExecuteRunRequest(@NotNull UUID releaseId, @NotNull JsonNode input) {}
    record PreviewRunRequest(@NotNull JsonNode input) {}
}
