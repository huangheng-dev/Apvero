package io.apvero.platform.runtime.internal;

import io.apvero.platform.release.ReleaseBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class RuntimeReleaseCompatibility {
    private static final Pattern LEGACY_ZERO_SEMANTIC_VERSION =
            Pattern.compile("^([a-z0-9][a-z0-9._:/-]*)@([1-9][0-9]*)\\.0\\.0$");

    ReleaseBundle forExecution(ReleaseBundle stored) {
        if (!"1.0".equals(stored.manifest().path("schemaVersion").stringValue(""))) {
            return stored;
        }
        ObjectNode normalized = (ObjectNode) stored.manifest().deepCopy();
        normalizeReference(normalized, "modelRouteVersion");
        normalizeReference(normalized, "promptVersion");
        if (normalized.equals(stored.manifest())) {
            return stored;
        }
        return new ReleaseBundle(
                stored.id(),
                stored.tenantId(),
                stored.workspaceId(),
                stored.applicationId(),
                stored.version(),
                stored.artifactDigest(),
                normalized,
                stored.status(),
                stored.purpose(),
                stored.expiresAt(),
                stored.createdAt());
    }

    private static void normalizeReference(ObjectNode manifest, String field) {
        JsonNode value = manifest.get(field);
        if (value == null || !value.isString()) {
            return;
        }
        Matcher legacy = LEGACY_ZERO_SEMANTIC_VERSION.matcher(value.stringValue());
        if (legacy.matches()) {
            manifest.put(field, legacy.group(1) + "@" + legacy.group(2));
        }
    }
}
