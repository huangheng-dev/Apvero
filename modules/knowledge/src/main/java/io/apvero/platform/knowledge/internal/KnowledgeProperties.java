package io.apvero.platform.knowledge.internal;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("apvero.knowledge")
record KnowledgeProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://ai-worker:8090") URI workerBaseUri) {

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
    }
}
