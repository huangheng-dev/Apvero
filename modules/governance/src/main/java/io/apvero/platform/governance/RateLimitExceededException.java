package io.apvero.platform.governance;

public final class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException() {
        super("The execution was rejected by a rate policy.");
    }
}
