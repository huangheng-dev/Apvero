package io.apvero.platform.capability.internal;

import io.apvero.platform.capability.EmbeddingInputUnitEstimator;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ConservativeUtf8EmbeddingInputUnitEstimator implements EmbeddingInputUnitEstimator {
    public static final String ALGORITHM_VERSION = "apvero-utf8-byte-v1";

    @Override
    public String algorithmVersion() {
        return ALGORITHM_VERSION;
    }

    @Override
    public long estimateUnits(String text) {
        return Math.max(1L, Objects.requireNonNull(
                text, "APVERO_EMBEDDING_ESTIMATOR_TEXT_REQUIRED").getBytes(StandardCharsets.UTF_8).length);
    }
}
