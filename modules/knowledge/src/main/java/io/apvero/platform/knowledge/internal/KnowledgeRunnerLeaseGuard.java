package io.apvero.platform.knowledge.internal;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeRunnerLeaseGuard {
    KnowledgeRunnerLeaseGuard(
            KnowledgeProperties knowledge,
            KnowledgeRunnerProperties runner,
            WebCaptureProperties web) {
        Duration workerBudget = knowledge.workerReadTimeout();
        Duration webRequestBudget = web.connectTimeout().plus(web.readTimeout());
        Duration webBudget = webRequestBudget.multipliedBy((long) web.maxRedirects() + 1L);
        Duration required = workerBudget.compareTo(webBudget) >= 0 ? workerBudget : webBudget;
        if (runner.leaseDuration().compareTo(required) <= 0) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_RUNNER_LEASE_TOO_SHORT");
        }
    }
}
