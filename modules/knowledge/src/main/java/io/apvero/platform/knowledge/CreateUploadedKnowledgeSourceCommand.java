package io.apvero.platform.knowledge;

import java.io.InputStream;

public record CreateUploadedKnowledgeSourceCommand(
        String name,
        String originalFilename,
        String declaredMediaType,
        long declaredSize,
        InputStream content) {}
