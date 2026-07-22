package io.apvero.platform.knowledge;

public record SourceIngestionReceipt(
        KnowledgeSource source,
        KnowledgeSourceRevision revision,
        KnowledgeIngestionJob job) {}
