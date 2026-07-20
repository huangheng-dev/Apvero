package io.apvero.platform.application;

import java.util.UUID;

public final class ApplicationNotFoundException extends RuntimeException {
    public ApplicationNotFoundException(UUID applicationId) {
        super("Application %s was not found in the current workspace.".formatted(applicationId));
    }
}
