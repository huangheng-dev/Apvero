package io.apvero.platform.release;

public final class ReleaseException extends RuntimeException {
    private final String code;
    private final Category category;

    public ReleaseException(String code, Category category) {
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
        CONFLICT
    }
}
