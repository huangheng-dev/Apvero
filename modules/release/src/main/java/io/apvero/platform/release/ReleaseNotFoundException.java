package io.apvero.platform.release;

import java.util.UUID;

public final class ReleaseNotFoundException extends RuntimeException {
    public ReleaseNotFoundException(UUID releaseId) {
        super("Release %s was not found in the current workspace.".formatted(releaseId));
    }
}
