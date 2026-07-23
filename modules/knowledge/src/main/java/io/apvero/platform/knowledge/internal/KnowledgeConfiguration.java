package io.apvero.platform.knowledge.internal;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        KnowledgeProperties.class,
        KnowledgeRunnerProperties.class,
        WebCaptureProperties.class
})
class KnowledgeConfiguration {

    @Bean
    HttpClient knowledgeWorkerHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
