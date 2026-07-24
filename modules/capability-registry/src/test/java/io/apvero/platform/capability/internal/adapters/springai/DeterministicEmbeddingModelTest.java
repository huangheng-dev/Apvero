package io.apvero.platform.capability.internal.adapters.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class DeterministicEmbeddingModelTest {
    private static final Map<String, String> GOLDEN_HASHES = goldenHashes();
    private final DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();

    @Test
    void freezesAllFloatBitsAndHashesAcrossTheBilingualCorpus() throws Exception {
        for (Map.Entry<String, String> golden : GOLDEN_HASHES.entrySet()) {
            float[] actual = model.embed(goldenInput(golden.getKey()));
            float[] reference = independentReference(goldenInput(golden.getKey()));

            assertThat(actual)
                    .as(golden.getKey())
                    .containsExactly(reference);
            assertThat(vectorDigest(actual))
                    .as(golden.getKey())
                    .isEqualTo(golden.getValue());
            assertThat(l2Norm(actual))
                    .as(golden.getKey())
                    .isCloseTo(1d, within(0.000_001d));
        }
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    @ResourceLock(Resources.TIME_ZONE)
    void preservesRequestOrderAndIgnoresLocaleAndTimezone() {
        Locale originalLocale = Locale.getDefault();
        TimeZone originalTimezone = TimeZone.getDefault();
        try {
            EmbeddingResponse baseline = call(List.of(
                    goldenInput("english"), goldenInput("chinese"), goldenInput("emoji")));
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Chatham"));
            EmbeddingResponse variant = call(List.of(
                    goldenInput("english"), goldenInput("chinese"), goldenInput("emoji")));

            assertThat(baseline.getResults()).extracting(Embedding::getIndex)
                    .containsExactly(0, 1, 2);
            assertThat(variant.getResults()).extracting(Embedding::getIndex)
                    .containsExactly(0, 1, 2);
            assertThat(variant.getResults()).extracting(item -> vectorDigest(item.getOutput()))
                    .containsExactly(
                            GOLDEN_HASHES.get("english"),
                            GOLDEN_HASHES.get("chinese"),
                            GOLDEN_HASHES.get("emoji"));
            assertThat(baseline.getMetadata().getModel())
                    .isEqualTo(DeterministicEmbeddingModel.IDENTITY);
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalTimezone);
        }
    }

    @Test
    void implementsTheSpringAiContractWithoutDiscoveryCalls() {
        assertThat(model.dimensions()).isEqualTo(256);
        assertThat(model.embed(new Document("document text")))
                .containsExactly(model.embed("document text"));
        assertThat(DeterministicEmbeddingModel.REPLAY_POLICY.name()).isEqualTo("SAFE_REPLAY");

        EmbeddingOptions wrongDimension = EmbeddingOptions.builder().dimensions(384).build();
        assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("text"), wrongDimension)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EMBEDDING_DIMENSION_MISMATCH");

        EmbeddingOptions wrongModel = EmbeddingOptions.builder().model("mutable-latest").build();
        assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("text"), wrongModel)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_EMBEDDING_MODEL_IDENTITY_MISMATCH");
    }

    @Test
    void preservesUnicodeCompositionAndLineEndingsExactly() {
        assertThat(vectorDigest(model.embed("Cafe\u0301")))
                .isNotEqualTo(vectorDigest(model.embed("Café")));
        assertThat(vectorDigest(model.embed("line one\r\n第二行\r\n")))
                .isNotEqualTo(vectorDigest(model.embed("line one\n第二行\n")));
        assertThat(vectorDigest(model.embed("")))
                .isNotEqualTo(vectorDigest(model.embed(" ")));
    }

    private EmbeddingResponse call(List<String> inputs) {
        EmbeddingOptions options = EmbeddingOptions.builder()
                .model(DeterministicEmbeddingModel.IDENTITY)
                .dimensions(DeterministicEmbeddingModel.DIMENSION)
                .build();
        return model.call(new EmbeddingRequest(inputs, options));
    }

    private static Map<String, String> goldenHashes() {
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("english", "100ca8cc9507becd61dfda88d74a2cd0de3a7dfb862933b0cc03ac92d7d2ab4e");
        hashes.put("chinese", "7f8d8b0a063cb562a5b5b10e30efc4111b9f868d060f8a2b99b028407d79d107");
        hashes.put("mixed", "ef42fe9d95d7ee19e38646263cd1711b5ae29a956bbcd8cf29d2abae3ecbf24b");
        hashes.put("combining", "9dff7a6dadaab94d38bd50a9ed803187d538496e215316392787e6d8b7641a9c");
        hashes.put("emoji", "a1909aa54af3a81451ce637a14abaf555cbcdca0e1144c16e5c8b8e6eda59947");
        hashes.put("crlf", "8945f10e0d240071fe230b4a183cd5521b697c7f3c8133a63885b2286913f2c7");
        hashes.put("long", "17db17de4f3393cf3ce3c3c734b139e0fba017622a3434dc01dc2148a473c72c");
        return Map.copyOf(hashes);
    }

    private static String goldenInput(String id) {
        return switch (id) {
            case "english" -> "Apvero builds reproducible AI applications.";
            case "chinese" -> "Apvero 构建可复现的人工智能应用。";
            case "mixed" -> "Apvero 支持 English 与中文。";
            case "combining" -> "Cafe\u0301 ≠ Café";
            case "emoji" -> "治理 🔒 embeddings 🧭";
            case "crlf" -> "line one\r\n第二行\r\n";
            case "long" -> "long-token-" + "x".repeat(4_096);
            default -> throw new IllegalArgumentException("Unknown golden input: " + id);
        };
    }

    private static float[] independentReference(String text) throws Exception {
        byte[] domain = DeterministicEmbeddingModel.IDENTITY.getBytes(StandardCharsets.UTF_8);
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        double[] unnormalized = new double[DeterministicEmbeddingModel.DIMENSION];
        int position = 0;
        for (int block = 0; block < 8; block++) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain);
            digest.update((byte) 0);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(block).array());
            digest.update((byte) 0);
            for (byte value : digest.digest(input)) {
                unnormalized[position++] = (value + 0.5d) / 128d;
            }
        }
        double squaredNorm = 0d;
        for (double value : unnormalized) {
            squaredNorm = StrictMath.fma(value, value, squaredNorm);
        }
        double norm = StrictMath.sqrt(squaredNorm);
        float[] result = new float[unnormalized.length];
        for (int index = 0; index < unnormalized.length; index++) {
            result[index] = (float) (unnormalized[index] / norm);
        }
        return result;
    }

    private static String vectorDigest(float[] vector) {
        try {
            ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES);
            for (float value : vector) {
                bytes.putFloat(value);
            }
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static double l2Norm(float[] vector) {
        double squaredNorm = 0d;
        for (float value : vector) {
            squaredNorm = StrictMath.fma(value, value, squaredNorm);
        }
        return StrictMath.sqrt(squaredNorm);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
