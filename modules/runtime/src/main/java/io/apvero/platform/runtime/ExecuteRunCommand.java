package io.apvero.platform.runtime;

import tools.jackson.databind.JsonNode;
import java.util.UUID;

public record ExecuteRunCommand(UUID releaseId, JsonNode input) {}
