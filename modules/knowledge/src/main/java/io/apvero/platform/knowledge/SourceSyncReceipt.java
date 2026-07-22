package io.apvero.platform.knowledge;

public record SourceSyncReceipt(Outcome outcome, KnowledgeSource source, KnowledgeIngestionJob job) {
    public enum Outcome {
        SCHEDULED
    }
}
