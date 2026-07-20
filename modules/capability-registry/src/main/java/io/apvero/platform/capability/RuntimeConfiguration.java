package io.apvero.platform.capability;

import java.math.BigDecimal;
import java.util.Arrays;

public final class RuntimeConfiguration implements AutoCloseable {
    private final String providerType;
    private final String providerId;
    private final String baseUrl;
    private final String modelKey;
    private final char[] apiKey;
    private final String systemPrompt;
    private final int timeoutMs;
    private final int maxOutputTokens;
    private final BigDecimal temperature;
    private final long inputCostMicrosPerMillion;
    private final long outputCostMicrosPerMillion;
    private final boolean reasoning;

    public RuntimeConfiguration(
            String providerType, String providerId, String baseUrl, String modelKey, char[] apiKey,
            String systemPrompt, int timeoutMs, int maxOutputTokens, BigDecimal temperature,
            long inputCostMicrosPerMillion, long outputCostMicrosPerMillion, boolean reasoning) {
        this.providerType = providerType;
        this.providerId = providerId;
        this.baseUrl = baseUrl;
        this.modelKey = modelKey;
        this.apiKey = apiKey;
        this.systemPrompt = systemPrompt;
        this.timeoutMs = timeoutMs;
        this.maxOutputTokens = maxOutputTokens;
        this.temperature = temperature;
        this.inputCostMicrosPerMillion = inputCostMicrosPerMillion;
        this.outputCostMicrosPerMillion = outputCostMicrosPerMillion;
        this.reasoning = reasoning;
    }

    public String providerType() { return providerType; }
    public String providerId() { return providerId; }
    public String baseUrl() { return baseUrl; }
    public String modelKey() { return modelKey; }
    public char[] apiKey() { return apiKey; }
    public String systemPrompt() { return systemPrompt; }
    public int timeoutMs() { return timeoutMs; }
    public int maxOutputTokens() { return maxOutputTokens; }
    public BigDecimal temperature() { return temperature; }
    public long inputCostMicrosPerMillion() { return inputCostMicrosPerMillion; }
    public long outputCostMicrosPerMillion() { return outputCostMicrosPerMillion; }
    public boolean reasoning() { return reasoning; }

    @Override
    public void close() {
        Arrays.fill(apiKey, '\0');
    }
}
