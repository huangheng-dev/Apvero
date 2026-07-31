package io.apvero.platform.runtime;

public final class RunEvidenceException extends RuntimeException {
    private final String code;
    private final Category category;

    public RunEvidenceException(String code, Category category) {
        super(code);
        this.code = code;
        this.category = category;
    }

    public String code() {
        return code;
    }

    public Category category() {
        return category;
    }

    public enum Category {
        BAD_REQUEST,
        NOT_FOUND,
        CONFLICT
    }
}
