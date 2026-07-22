package io.apvero.platform.knowledge.internal;

import io.apvero.platform.knowledge.KnowledgeSource;
import java.util.Arrays;

record KnowledgeCapturedSnapshot(
        KnowledgeSource.Type sourceType,
        String mediaType,
        String originalFilename,
        String contentDigest,
        byte[] bytes) {

    KnowledgeCapturedSnapshot {
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
