package io.apvero.platform.knowledge;

import java.io.InputStream;

public record AddUploadedKnowledgeSourceRevisionCommand(
        String originalFilename,
        String declaredMediaType,
        long declaredSize,
        InputStream content) {}
