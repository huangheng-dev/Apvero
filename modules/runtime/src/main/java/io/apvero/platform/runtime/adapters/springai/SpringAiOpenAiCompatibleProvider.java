package io.apvero.platform.runtime.adapters.springai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.apvero.platform.capability.CapabilityCatalog;
import io.apvero.platform.capability.RuntimeConfiguration;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.runtime.ProviderRequest;
import io.apvero.platform.runtime.ProviderResult;
import io.apvero.platform.runtime.RuntimeProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class SpringAiOpenAiCompatibleProvider implements RuntimeProvider {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_]{0,63})}}", Pattern.MULTILINE);
    private final CapabilityCatalog capabilities;
    private final ObjectMapper json;
    private final boolean enabled;

    public SpringAiOpenAiCompatibleProvider(
            CapabilityCatalog capabilities,
            ObjectMapper json,
            @Value("${apvero.providers.real-enabled:false}") boolean enabled) {
        this.capabilities = capabilities;
        this.json = json;
        this.enabled = enabled;
    }

    @Override
    public String id() {
        return "spring-ai-openai-compatible";
    }

    @Override
    public boolean supports(ReleaseBundle release) {
        JsonNode route = release.manifest().get("modelRouteVersion");
        return route != null && route.isString() && !route.stringValue().startsWith("local-deterministic@");
    }

    @Override
    public ProviderResult execute(ProviderRequest request) {
        if (!enabled) throw new IllegalStateException("Real provider execution is disabled by configuration.");
        String route = requiredText(request.release().manifest(), "modelRouteVersion");
        String promptVersion = requiredText(request.release().manifest(), "promptVersion");
        try (RuntimeConfiguration configuration = capabilities.resolve(request.release().workspaceId(), route, promptVersion)) {
            String systemPrompt = render(configuration.systemPrompt(), request.input());
            String message = request.input().path("message").stringValue(request.input().toString());
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .baseUrl(configuration.baseUrl())
                    .apiKey(new String(configuration.apiKey()))
                    .model(configuration.modelKey())
                    .timeout(Duration.ofMillis(configuration.timeoutMs()))
                    .maxRetries(0);
            if (configuration.reasoning()) {
                options.maxCompletionTokens(configuration.maxOutputTokens());
            } else {
                options.maxTokens(configuration.maxOutputTokens());
                if (configuration.temperature() != null) options.temperature(configuration.temperature().doubleValue());
            }
            OpenAiChatModel model = OpenAiChatModel.builder().options(options.build()).build();
            var response = model.call(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(message))));
            if (response.getResult() == null) throw new IllegalStateException("Provider returned no generation.");
            String text = response.getResult().getOutput().getText();
            Integer promptTokensValue = response.getMetadata().getUsage().getPromptTokens();
            Integer completionTokensValue = response.getMetadata().getUsage().getCompletionTokens();
            int promptTokens = promptTokensValue == null ? 0 : promptTokensValue;
            int completionTokens = completionTokensValue == null ? 0 : completionTokensValue;
            long cost = calculateCost(promptTokens, completionTokens, configuration);
            ObjectNode output = json.createObjectNode();
            output.put("message", text == null ? "" : text);
            output.put("model", configuration.modelKey());
            output.put("route", route);
            output.put("traceId", request.traceId());
            return new ProviderResult(output, promptTokens, completionTokens, cost);
        }
    }

    private String render(String template, JsonNode input) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            JsonNode value = input.get(matcher.group(1));
            if (value == null || value.isNull()) throw new IllegalArgumentException("Missing Prompt variable: " + matcher.group(1));
            String replacement = value.isValueNode() ? value.asString() : value.toString();
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private long calculateCost(int promptTokens, int completionTokens, RuntimeConfiguration configuration) {
        BigDecimal input = BigDecimal.valueOf(promptTokens).multiply(BigDecimal.valueOf(configuration.inputCostMicrosPerMillion()));
        BigDecimal output = BigDecimal.valueOf(completionTokens).multiply(BigDecimal.valueOf(configuration.outputCostMicrosPerMillion()));
        return input.add(output).divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw new IllegalArgumentException("Release manifest is missing " + field + ".");
        return value.stringValue();
    }
}
