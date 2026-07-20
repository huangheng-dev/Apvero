package io.apvero.platform.runtime;

import tools.jackson.databind.JsonNode;
import io.apvero.platform.release.ReleaseBundle;

public record ProviderRequest(ReleaseBundle release, JsonNode input, String traceId) {}
