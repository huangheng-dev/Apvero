package io.apvero.platform.application;

public record CreateApplicationCommand(
        String slug,
        String name,
        String description,
        RuntimeMode runtimeMode) {}
