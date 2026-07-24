package io.apvero.platform.capability.internal.adapters.springai;

import io.apvero.platform.capability.EmbeddingReplayPolicy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;

public final class DeterministicEmbeddingModel implements EmbeddingModel {
    public static final String IDENTITY = "apvero-deterministic-embedding@1.0.0";
    public static final int DIMENSION = 256;
    public static final EmbeddingReplayPolicy REPLAY_POLICY = EmbeddingReplayPolicy.SAFE_REPLAY;

    private static final byte[] DOMAIN = IDENTITY.getBytes(StandardCharsets.UTF_8);
    private static final int DIGEST_BLOCKS = 8;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        Objects.requireNonNull(request, "APVERO_EMBEDDING_REQUEST_REQUIRED");
        validateOptions(request.getOptions());
        List<String> instructions = Objects.requireNonNull(
                request.getInstructions(), "APVERO_EMBEDDING_INPUTS_REQUIRED");
        if (instructions.isEmpty()) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_INPUTS_EMPTY");
        }

        List<Embedding> embeddings = new ArrayList<>(instructions.size());
        for (int index = 0; index < instructions.size(); index++) {
            embeddings.add(new Embedding(vector(instructions.get(index)), index));
        }
        return new EmbeddingResponse(
                embeddings,
                new EmbeddingResponseMetadata(IDENTITY, new EmptyUsage()));
    }

    @Override
    public float[] embed(Document document) {
        Objects.requireNonNull(document, "APVERO_EMBEDDING_DOCUMENT_REQUIRED");
        return vector(Objects.requireNonNull(
                document.getText(), "APVERO_EMBEDDING_DOCUMENT_TEXT_REQUIRED"));
    }

    @Override
    public int dimensions() {
        return DIMENSION;
    }

    private static void validateOptions(EmbeddingOptions options) {
        if (options == null) {
            return;
        }
        if (options.getDimensions() != null && options.getDimensions() != DIMENSION) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_DIMENSION_MISMATCH");
        }
        if (options.getModel() != null
                && !options.getModel().isBlank()
                && !IDENTITY.equals(options.getModel())) {
            throw new IllegalArgumentException("APVERO_EMBEDDING_MODEL_IDENTITY_MISMATCH");
        }
    }

    private static float[] vector(String text) {
        byte[] input = Objects.requireNonNull(
                text, "APVERO_EMBEDDING_TEXT_REQUIRED").getBytes(StandardCharsets.UTF_8);
        double[] components = new double[DIMENSION];
        int offset = 0;
        for (int block = 0; block < DIGEST_BLOCKS; block++) {
            MessageDigest digest = sha256();
            digest.update(DOMAIN);
            digest.update((byte) 0);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(block).array());
            digest.update((byte) 0);
            byte[] bytes = digest.digest(input);
            for (byte value : bytes) {
                components[offset++] = (value + 0.5d) / 128d;
            }
        }

        double squaredNorm = 0d;
        for (double component : components) {
            squaredNorm = StrictMath.fma(component, component, squaredNorm);
        }
        double norm = StrictMath.sqrt(squaredNorm);
        if (!(norm > 0d) || !Double.isFinite(norm)) {
            throw new IllegalStateException("APVERO_EMBEDDING_VECTOR_ZERO_NORM");
        }

        float[] vector = new float[DIMENSION];
        for (int index = 0; index < components.length; index++) {
            vector[index] = (float) (components[index] / norm);
        }
        return vector;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("APVERO_SHA256_UNAVAILABLE", exception);
        }
    }
}
