package io.apvero.platform.release;

import tools.jackson.databind.JsonNode;

public record CreateReleaseCommand(String version, JsonNode manifest) {}
