package io.apvero.platform.knowledge;

public record CreateInlineKnowledgeSourceCommand(KnowledgeSource.Type sourceType, String name, String content) {}
