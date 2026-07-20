package io.apvero.platform.runtime.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.runtime.ProviderRequest;
import io.apvero.platform.runtime.ProviderResult;
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
        return route != null && route.isString() && route.stringValue().startsWith(ID + "@");
    }

    @Override
    public ProviderResult execute(ProviderRequest request) {
        JsonNode messageNode = request.input().get("message");
        String message = messageNode != null && messageNode.isString()
                ? messageNode.stringValue()
                : request.input().toString();
        ObjectNode output = json.createObjectNode();
        output.put("message", "Apvero received: " + message);
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
}
