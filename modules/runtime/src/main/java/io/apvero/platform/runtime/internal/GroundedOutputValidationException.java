package io.apvero.platform.runtime.internal;

final class GroundedOutputValidationException extends RuntimeException {
    private final String code;

    GroundedOutputValidationException(String code) {
        super(code);
        this.code = code;
    }

    String code() {
        return code;
    }
}
