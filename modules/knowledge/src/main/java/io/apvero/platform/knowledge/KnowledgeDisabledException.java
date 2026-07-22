package io.apvero.platform.knowledge;

public final class KnowledgeDisabledException extends RuntimeException {

    public static final String CODE = "APVERO_KNOWLEDGE_DISABLED";

    public KnowledgeDisabledException() {
        super(CODE);
    }
}
