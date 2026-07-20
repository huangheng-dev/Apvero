package io.apvero.platform;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
class PlatformInfoController {

    private final BuildProperties buildProperties;

    PlatformInfoController(BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping
    PlatformInfo platformInfo() {
        return new PlatformInfo(
                "Apvero",
                buildProperties.getVersion(),
                "ready",
                "en",
                List.of("en", "zh-CN"),
                Map.of(
                        "applicationRoot", true,
                        "immutableReleaseBundle", true,
                        "deterministicLocalProvider", true,
                        "secretReferences", true,
                        "modelRoutes", true,
                        "promptVersions", true,
                        "previewRuns", true),
                Instant.now());
    }

    record PlatformInfo(
            String name,
            String version,
            String status,
            String sourceLocale,
            List<String> supportedLocales,
            Map<String, Boolean> capabilities,
            Instant serverTime) {}
}
