package io.apvero.platform.knowledge;

public final class KnowledgeException extends RuntimeException {
    private final String code;
    private final Category category;

    public KnowledgeException(String code, Category category) {
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
        CONFLICT,
        CONTENT_TOO_LARGE,
        UNSUPPORTED_MEDIA,
        UNPROCESSABLE
    }
}
