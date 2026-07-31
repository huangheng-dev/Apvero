package io.apvero.platform.application;

public final class ApplicationKnowledgeBindingException extends RuntimeException {
    private final String code;
    private final Category category;

    public ApplicationKnowledgeBindingException(String code, Category category) {
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
