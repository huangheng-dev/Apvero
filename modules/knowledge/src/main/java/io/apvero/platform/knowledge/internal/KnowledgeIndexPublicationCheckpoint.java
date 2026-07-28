package io.apvero.platform.knowledge.internal;

@FunctionalInterface
interface KnowledgeIndexPublicationCheckpoint {
    void after(Stage stage);

    enum Stage {
        ARTIFACT_PERSISTED,
        VERSION_INSERTED,
        BUILD_COMPLETED,
        INDEX_UPDATED,
        AUDIT_APPENDED
    }
}
