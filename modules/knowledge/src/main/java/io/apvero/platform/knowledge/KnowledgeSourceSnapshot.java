package io.apvero.platform.knowledge;

import java.util.Arrays;

public record KnowledgeSourceSnapshot(String contentDigest, String mediaType, String filename, byte[] bytes) {
    public KnowledgeSourceSnapshot {
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
