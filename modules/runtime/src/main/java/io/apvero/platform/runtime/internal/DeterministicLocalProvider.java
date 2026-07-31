package io.apvero.platform.runtime.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.runtime.ProviderRequest;
import io.apvero.platform.runtime.ProviderResult;
import io.apvero.platform.runtime.ProviderExecutionException;
import io.apvero.platform.runtime.ProviderFailureDisposition;
import io.apvero.platform.runtime.RuntimeProvider;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
final class DeterministicLocalProvider implements RuntimeProvider {
    static final String ID = "local-deterministic";
    private final ObjectMapper json;

    DeterministicLocalProvider(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(ReleaseBundle release) {
        JsonNode route = release.manifest().get("modelRouteVersion");
        return supportsChatManifest(release.manifest())
                && route != null
                && route.isString()
                && route.stringValue().startsWith(ID + "@");
    }

    @Override
    public ProviderResult execute(ProviderRequest request) {
        boolean rag = "RAG".equals(
                request.release().manifest().path("runtimeMode").stringValue(""));
        if (rag && request.groundingContext() == null) {
            throw new ProviderExecutionException(
                    "APVERO_RUNTIME_GROUNDING_CONTEXT_REQUIRED",
                    ProviderFailureDisposition.SAFE_TO_FAIL);
        }
        JsonNode messageNode = request.input().get("message");
        String message = messageNode != null && messageNode.isString()
                ? messageNode.stringValue()
                : request.input().toString();
        ObjectNode output = json.createObjectNode();
        if (rag) {
            ObjectNode grounded = json.createObjectNode();
            grounded.put("schemaVersion", "1.0");
            grounded.put("status", "GROUNDED");
            grounded.put("answer", "Apvero grounded response: " + message);
            grounded.putArray("citationMarkers").add("[K1]");
            output.put("message", grounded.toString());
            output.put("groundingHitCount", request.groundingContext().hitCount());
        } else {
            output.put("message", "Apvero received: " + message);
        }
        output.put("mode", "deterministic-local");
        output.put("releaseDigest", request.release().artifactDigest());
        output.put("traceId", request.traceId());
        int promptTokens = approximateTokens(request.input().toString());
        int completionTokens = approximateTokens(output.toString());
        return new ProviderResult(output, promptTokens, completionTokens, 0L);
    }

    private int approximateTokens(String text) {
        return Math.max(1, text.getBytes(StandardCharsets.UTF_8).length / 4);
    }

    private static boolean supportsChatManifest(JsonNode manifest) {
        String schemaVersion = manifest.path("schemaVersion").stringValue("");
        return "1.0".equals(schemaVersion)
                || ("1.1".equals(schemaVersion)
                        && ("CHAT".equals(manifest.path("runtimeMode").stringValue(""))
                        || "RAG".equals(manifest.path("runtimeMode").stringValue(""))));
    }

    @Override
    public ProviderFailureDisposition failureDisposition(RuntimeException failure) {
        return ProviderFailureDisposition.SAFE_TO_FAIL;
    }
}
