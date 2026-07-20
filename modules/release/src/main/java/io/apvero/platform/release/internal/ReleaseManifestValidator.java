package io.apvero.platform.release.internal;

import tools.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class ReleaseManifestValidator {
    private static final List<String> REQUIRED_FIELDS = List.of(
            "schemaVersion",
            "modelRouteVersion",
            "promptVersion",
            "outputSchemaVersion",
            "knowledgeIndexVersions",
            "capabilityVersions",
            "policyVersions",
            "memoryPolicyVersion",
            "evaluationReportVersion",
            "runtimeParameters");

    void validate(JsonNode manifest) {
        if (manifest == null || !manifest.isObject()) {
            throw new IllegalArgumentException("Release manifest must be a JSON object.");
        }
        List<String> missing = REQUIRED_FIELDS.stream()
                .filter(field -> manifest.get(field) == null || manifest.get(field).isNull())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Release manifest is missing pinned fields: " + String.join(", ", missing));
        }
        if (containsLatestReference(manifest)) {
            throw new IllegalArgumentException("Release manifests cannot reference latest versions.");
        }
    }

    private boolean containsLatestReference(JsonNode node) {
        if (node.isString()) {
            String value = node.stringValue();
            return value.equalsIgnoreCase("latest") || value.toLowerCase(java.util.Locale.ROOT).endsWith("@latest");
        }
        if (node.isContainer()) {
            for (JsonNode child : node) {
                if (containsLatestReference(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
