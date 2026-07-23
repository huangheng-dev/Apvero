package io.apvero.platform.knowledge.internal;

final class KnowledgeWorkerException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    KnowledgeWorkerException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    String code() {
        return code;
    }

    boolean retryable() {
        return retryable;
    }
}
