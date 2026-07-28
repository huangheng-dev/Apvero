package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingCapability;
import io.apvero.platform.capability.EmbeddingInputUnitEstimator;
import io.apvero.platform.governance.ExecutionGovernance;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        KnowledgeProperties.class,
        KnowledgeRunnerProperties.class,
        KnowledgeIndexBuildRunnerProperties.class,
        WebCaptureProperties.class
})
class KnowledgeConfiguration {

    @Bean
    HttpClient knowledgeWorkerHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "apvero.knowledge",
            name = "enabled",
            havingValue = "true")
    @ConditionalOnBean({EmbeddingCapability.class, EmbeddingInputUnitEstimator.class})
    KnowledgeEmbeddingBatchExecutor knowledgeEmbeddingBatchExecutor(
            KnowledgePersistenceRepository knowledge,
            KnowledgeIndexPersistenceRepository indexes,
            EmbeddingCapability embeddings,
            EmbeddingInputUnitEstimator estimator,
            KnowledgeEmbeddingEntryBatchWriter writer) {
        return new KnowledgeEmbeddingBatchExecutor(
                knowledge, indexes, embeddings, estimator, writer, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(KnowledgeEmbeddingBatchExecutor.class)
    KnowledgeEmbeddingLeaseCoordinator knowledgeEmbeddingLeaseCoordinator(
            KnowledgeIndexBuildTransitionKernel kernel,
            KnowledgeEmbeddingBatchExecutor batches,
            ExecutionGovernance governance) {
        return new KnowledgeEmbeddingLeaseCoordinator(kernel, batches, governance);
    }

    @Bean
    @ConditionalOnBean(KnowledgeEmbeddingLeaseCoordinator.class)
    KnowledgeIndexBuildEmbeddingOrchestrator knowledgeIndexBuildEmbeddingOrchestrator(
            KnowledgeEmbeddingBatchExecutor batches,
            KnowledgeEmbeddingLeaseCoordinator coordinator,
            KnowledgeIndexBuildTransitionKernel kernel,
            EmbeddingCapability embeddings) {
        return new KnowledgeIndexBuildEmbeddingOrchestrator(
                batches, coordinator, kernel, embeddings);
    }
}
