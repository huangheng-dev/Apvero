package io.apvero.platform.knowledge.internal;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("apvero.knowledge")
record KnowledgeProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://ai-worker:8090") URI workerBaseUri,
        @DefaultValue("5242880") int maxInlineCharacters,
        @DefaultValue("5242880") int maxSnapshotBytes,
        @DefaultValue("2048") int maxDocxEntries,
        @DefaultValue("20971520") long maxDocxExpandedBytes) {

    KnowledgeProperties {
        if (workerBaseUri == null
                || workerBaseUri.getHost() == null
                || !("http".equalsIgnoreCase(workerBaseUri.getScheme())
                        || "https".equalsIgnoreCase(workerBaseUri.getScheme()))
                || workerBaseUri.getUserInfo() != null
                || workerBaseUri.getQuery() != null
                || workerBaseUri.getFragment() != null
                || !(workerBaseUri.getPath().isEmpty() || "/".equals(workerBaseUri.getPath()))) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_WORKER_BASE_URI_INVALID");
        }
        if (maxInlineCharacters < 1 || maxSnapshotBytes < 1) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_SOURCE_LIMIT_INVALID");
        }
        if (maxDocxEntries < 1 || maxDocxExpandedBytes < maxSnapshotBytes) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_DOCX_LIMIT_INVALID");
        }
    }
}
