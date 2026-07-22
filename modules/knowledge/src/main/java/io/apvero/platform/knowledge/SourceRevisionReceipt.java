package io.apvero.platform.knowledge;

public record SourceRevisionReceipt(
        Outcome outcome,
        KnowledgeSource source,
        KnowledgeSourceRevision revision,
        KnowledgeIngestionJob job) {

    public enum Outcome {
        CHANGED,
        UNCHANGED
    }
}
