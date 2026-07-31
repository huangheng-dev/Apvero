package io.apvero.platform.capability;

public final class CapabilityExecutionException extends RuntimeException {
    private final String code;

    public CapabilityExecutionException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
