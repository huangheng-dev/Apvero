package io.apvero.platform.capability;

public interface EmbeddingInputUnitEstimator {
    String algorithmVersion();

    long estimateUnits(String text);
}
