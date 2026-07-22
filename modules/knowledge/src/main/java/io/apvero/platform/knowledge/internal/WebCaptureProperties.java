package io.apvero.platform.knowledge.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("apvero.knowledge.web-capture")
record WebCaptureProperties(
        @DefaultValue("5") int maxRedirects,
        @DefaultValue("65536") int maxHeaderBytes,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {

    WebCaptureProperties {
        if (maxRedirects < 0 || maxRedirects > 20 || maxHeaderBytes < 1024 || maxHeaderBytes > 262_144) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_WEB_LIMIT_INVALID");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_WEB_TIMEOUT_INVALID");
        }
        if (connectTimeout.compareTo(Duration.ofMillis(1)) < 0
                || readTimeout.compareTo(Duration.ofMillis(1)) < 0
                || connectTimeout.compareTo(Duration.ofMinutes(1)) > 0
                || readTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_WEB_TIMEOUT_INVALID");
        }
    }
}
