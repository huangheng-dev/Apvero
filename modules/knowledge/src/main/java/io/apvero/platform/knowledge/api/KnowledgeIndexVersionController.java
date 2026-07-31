package io.apvero.platform.knowledge.api;

import io.apvero.platform.knowledge.KnowledgeIndexVersion;
import io.apvero.platform.knowledge.KnowledgeIndexVersionCatalog;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-index-versions")
final class KnowledgeIndexVersionController {
    private final KnowledgeIndexVersionCatalog versions;

    KnowledgeIndexVersionController(KnowledgeIndexVersionCatalog versions) {
        this.versions = versions;
    }

    @GetMapping
    List<KnowledgeIndexVersion> list(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @RequestParam(required = false) UUID indexId) {
        return versions.list(workspaceId, indexId);
    }
}
