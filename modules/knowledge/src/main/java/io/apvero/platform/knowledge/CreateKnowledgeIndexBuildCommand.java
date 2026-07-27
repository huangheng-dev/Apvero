package io.apvero.platform.knowledge;

import java.util.List;
import java.util.UUID;

public record CreateKnowledgeIndexBuildCommand(
        String version,
        UUID embeddingRouteId,
        List<UUID> sourceRevisionIds) {

    public CreateKnowledgeIndexBuildCommand {
        sourceRevisionIds = sourceRevisionIds == null ? null : List.copyOf(sourceRevisionIds);
    }
}
