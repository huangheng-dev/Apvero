package io.apvero.platform.knowledge.internal;

import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.KnowledgeDisabledException;
import org.springframework.stereotype.Service;

@Service
final class DefaultKnowledgeAvailability implements KnowledgeAvailability {

    private final KnowledgeProperties properties;

    DefaultKnowledgeAvailability(KnowledgeProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return properties.enabled();
    }

    @Override
    public void requireEnabled() {
        if (!isEnabled()) {
            throw new KnowledgeDisabledException();
        }
    }
}
