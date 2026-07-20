package io.apvero.platform.application;

import java.util.UUID;

public record BindApplicationDraftCommand(UUID modelRouteId, UUID promptVersionId) {}
