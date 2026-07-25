package io.apvero.platform.capability.internal.adapters.springai;

import io.apvero.platform.capability.EmbeddingReplayPolicy;
import io.apvero.platform.governance.ResolvedSecret;
import io.apvero.platform.governance.SecretReferenceCatalog;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class OpenAiCompatibleEmbeddingAdapter {
    public static final String IDENTITY = "spring-ai-openai-compatible-embedding";
    public static final EmbeddingReplayPolicy REPLAY_POLICY =
            EmbeddingReplayPolicy.RECONCILIATION_REQUIRED;

    private final SecretReferenceCatalog secrets;
    private final boolean enabled;

    public OpenAiCompatibleEmbeddingAdapter(
            SecretReferenceCatalog secrets,
            @Value("${apvero.providers.real-enabled:false}") boolean enabled) {
        this.secrets = secrets;
        this.enabled = enabled;
    }

    public OpenAiCompatibleEmbeddingResult execute(OpenAiCompatibleEmbeddingRequest request) {
        Objects.requireNonNull(request, "APVERO_EMBEDDING_REQUEST_REQUIRED");
        if (!enabled) {
            throw new IllegalStateException("APVERO_REAL_PROVIDER_DISABLED");
        }

        long startedAt = System.nanoTime();
        EmbeddingResponse response;
        try (ResolvedSecret secret = resolveSecret(request)) {
            try {
                OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                        .baseUrl(request.baseUrl())
                        .apiKey(new String(secret.value()))
                        .model(request.modelKey())
                        .dimensions(request.dimension())
                        .timeout(Duration.ofMillis(request.timeoutMs()))
                        .maxRetries(0)
                        .build();
                OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                        .options(options)
                        .build();
                response = model.call(new EmbeddingRequest(request.orderedInputs(), options));
            } catch (RuntimeException exception) {
                throw normalizedProviderFailure(exception);
            }
        }
        return normalize(request, response, elapsedMillis(startedAt));
    }

    private ResolvedSecret resolveSecret(OpenAiCompatibleEmbeddingRequest request) {
        try {
            return secrets.resolve(request.workspaceId(), request.secretReferenceId());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("APVERO_EMBEDDING_SECRET_UNAVAILABLE");
        }
    }

    private OpenAiCompatibleEmbeddingResult normalize(
            OpenAiCompatibleEmbeddingRequest request,
            EmbeddingResponse response,
            long latencyMillis) {
        if (response == null || response.getResults() == null
                || response.getResults().size() != request.orderedInputs().size()) {
            throw new IllegalStateException("APVERO_EMBEDDING_OUTPUT_MAPPING_INVALID");
        }

        List<float[]> orderedVectors = new ArrayList<>(response.getResults().size());
        for (int position = 0; position < response.getResults().size(); position++) {
            Embedding embedding = response.getResults().get(position);
            if (embedding == null || embedding.getIndex() == null || embedding.getIndex() != position) {
                throw new IllegalStateException("APVERO_EMBEDDING_OUTPUT_MAPPING_INVALID");
            }
            float[] vector = embedding.getOutput();
            validateVector(vector, request.dimension());
            orderedVectors.add(vector);
        }

        Long actualInputUnits = null;
        if (response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null && usage.getPromptTokens() != null) {
                actualInputUnits = usage.getPromptTokens().longValue();
            }
        }
        return new OpenAiCompatibleEmbeddingResult(
                orderedVectors, actualInputUnits, null, latencyMillis);
    }

    private static void validateVector(float[] vector, int expectedDimension) {
        if (vector == null || vector.length != expectedDimension) {
            throw new IllegalStateException("APVERO_EMBEDDING_OUTPUT_DIMENSION_MISMATCH");
        }
        double squaredNorm = 0d;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("APVERO_EMBEDDING_VECTOR_NON_FINITE");
            }
            squaredNorm += (double) value * value;
        }
        if (!(squaredNorm > 0d) || !Double.isFinite(squaredNorm)) {
            throw new IllegalStateException("APVERO_EMBEDDING_VECTOR_ZERO_NORM");
        }
    }

    private static RuntimeException normalizedProviderFailure(RuntimeException exception) {
        if (isTimeout(exception)) {
            return new IllegalStateException("APVERO_EMBEDDING_PROVIDER_TIMEOUT");
        }
        return new IllegalStateException("APVERO_EMBEDDING_PROVIDER_REJECTED");
    }

    private static boolean isTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")
                    || hasTimeoutMessage(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasTimeoutMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("timeout") || normalized.contains("timed out");
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }
}
