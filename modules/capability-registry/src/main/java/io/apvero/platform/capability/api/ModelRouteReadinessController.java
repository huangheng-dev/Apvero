package io.apvero.platform.capability.api;

import io.apvero.platform.capability.ModelRouteReadiness;
import io.apvero.platform.capability.ModelRouteReadinessCatalog;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/model-routes/readiness")
final class ModelRouteReadinessController {
    private final ModelRouteReadinessCatalog readiness;

    ModelRouteReadinessController(ModelRouteReadinessCatalog readiness) {
        this.readiness = readiness;
    }

    @GetMapping
    List<ModelRouteReadiness> list(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return readiness.list(workspaceId);
    }
}
