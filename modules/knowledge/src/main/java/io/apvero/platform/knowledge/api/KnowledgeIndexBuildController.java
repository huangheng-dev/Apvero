package io.apvero.platform.knowledge.api;

import io.apvero.platform.knowledge.CreateKnowledgeIndexBuildCommand;
import io.apvero.platform.knowledge.KnowledgeIndexBuild;
import io.apvero.platform.knowledge.KnowledgeIndexBuildCatalog;
import jakarta.servlet.http.HttpServletRequest;
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
final class KnowledgeIndexBuildController {
    private final KnowledgeIndexBuildCatalog builds;

    KnowledgeIndexBuildController(KnowledgeIndexBuildCatalog builds) {
        this.builds = builds;
    }

    @GetMapping("/knowledge-indexes/{indexId}/builds")
    List<KnowledgeIndexBuild> listBuilds(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID indexId) {
        return builds.list(workspaceId, indexId);
    }

    @PostMapping("/knowledge-indexes/{indexId}/builds")
    ResponseEntity<KnowledgeIndexBuild> createBuild(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID indexId,
            @RequestBody(required = false) CreateBuildRequest request,
            HttpServletRequest httpRequest) {
        CreateKnowledgeIndexBuildCommand command = request == null
                ? null
                : new CreateKnowledgeIndexBuildCommand(
                        request.version(),
                        request.embeddingRouteId(),
                        request.sourceRevisionIds());
        KnowledgeIndexBuild created = builds.create(
                workspaceId,
                indexId,
                command,
                KnowledgeController.context(httpRequest));
        return ResponseEntity.accepted().body(created);
    }

    @GetMapping("/knowledge-index-builds/{buildId}")
    KnowledgeIndexBuild getBuild(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID buildId) {
        return builds.get(workspaceId, buildId);
    }

    @PostMapping("/knowledge-index-builds/{buildId}/retry")
    ResponseEntity<KnowledgeIndexBuild> retryBuild(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID buildId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.accepted().body(
                builds.retry(workspaceId, buildId, KnowledgeController.context(httpRequest)));
    }

    @PostMapping("/knowledge-index-builds/{buildId}/cancel")
    KnowledgeIndexBuild cancelBuild(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID buildId,
            HttpServletRequest httpRequest) {
        return builds.cancel(workspaceId, buildId, KnowledgeController.context(httpRequest));
    }

    record CreateBuildRequest(
            String version,
            UUID embeddingRouteId,
            List<UUID> sourceRevisionIds) {}
}
