package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeIngestionRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIngestionRunner.class);

    private final KnowledgeAvailability availability;
    private final WorkspaceScopeCatalog workspaces;
    private final KnowledgePersistenceRepository repository;
    private final KnowledgeJobLeaseService leases;
    private final KnowledgeIngestionStepExecutor steps;
    private final KnowledgeRunnerProperties properties;
    private final MeterRegistry meters;
    private final String leaseOwner = "runner-" + UUID.randomUUID();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ExecutorService executor;

    KnowledgeIngestionRunner(
            KnowledgeAvailability availability,
            WorkspaceScopeCatalog workspaces,
            KnowledgePersistenceRepository repository,
            KnowledgeJobLeaseService leases,
            KnowledgeIngestionStepExecutor steps,
            KnowledgeRunnerProperties properties,
            MeterRegistry meters) {
        this.availability = availability;
        this.workspaces = workspaces;
        this.repository = repository;
        this.leases = leases;
        this.steps = steps;
        this.properties = properties;
        this.meters = meters;
        this.executor = Executors.newFixedThreadPool(properties.concurrency(),
                Thread.ofPlatform().name("apvero-knowledge-ingestion-", 0).factory());
    }

    @Scheduled(fixedDelayString = "${apvero.knowledge.runner.poll-interval:1s}")
    void poll() {
        if (!accepting.get() || !availability.isEnabled() || !properties.enabled()) {
            return;
        }
        int capacity = properties.concurrency() - inFlight.get();
        if (capacity <= 0) {
            return;
        }
        for (WorkspaceScope scope : workspaces.listForBackgroundProcessing()) {
            if (!accepting.get() || capacity <= 0) {
                break;
            }
            List<IngestionJobRow> claimed = leases.claim(scope, leaseOwner, capacity);
            for (IngestionJobRow job : claimed) {
                if (!submit(scope, job)) {
                    return;
                }
                capacity--;
            }
        }
    }

    private boolean submit(WorkspaceScope scope, IngestionJobRow job) {
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> run(scope, job));
            meters.counter("apvero.knowledge.ingestion.claimed", "step", metric(job.currentStep())).increment();
            Duration queueWait = Duration.between(job.createdAt(), OffsetDateTime.now(ZoneOffset.UTC));
            meters.timer("apvero.knowledge.ingestion.queue.wait", "step", metric(job.currentStep()))
                    .record(queueWait.isNegative() ? Duration.ZERO : queueWait);
            return true;
        } catch (RejectedExecutionException exception) {
            inFlight.decrementAndGet();
            return false;
        }
    }

    private void run(WorkspaceScope scope, IngestionJobRow claimed) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "succeeded";
        String errorCategory = "none";
        try {
            steps.execute(scope, claimed, leaseOwner);
        } catch (RuntimeException failure) {
            var classified = steps.classify(failure);
            outcome = "failed";
            errorCategory = classified.category().name().toLowerCase(java.util.Locale.ROOT);
            failIfStillOwned(scope, claimed, classified);
            LOGGER.warn("Knowledge ingestion step failed: step={}, category={}, code={}",
                    claimed.currentStep(), classified.category(), classified.code());
        } finally {
            sample.stop(Timer.builder("apvero.knowledge.ingestion.step.duration")
                    .tag("step", metric(claimed.currentStep()))
                    .tag("outcome", outcome)
                    .tag("error_category", errorCategory)
                    .register(meters));
            inFlight.decrementAndGet();
        }
    }

    private void failIfStillOwned(
            WorkspaceScope scope,
            IngestionJobRow originallyClaimed,
            KnowledgeJobLeaseService.KnowledgeJobFailure failure) {
        try {
            IngestionJobRow current = repository.findJob(scope, originallyClaimed.id()).orElse(null);
            if (current != null && leaseOwner.equals(current.leaseOwner())) {
                leases.fail(scope, current, leaseOwner, failure);
                meters.counter("apvero.knowledge.ingestion.failures",
                        "step", metric(current.currentStep()),
                        "error_category", failure.category().name().toLowerCase(java.util.Locale.ROOT),
                        "retryable", Boolean.toString(failure.retryable())).increment();
            }
        } catch (RuntimeException failureWhilePersisting) {
            LOGGER.error("Knowledge ingestion failure state could not be persisted; lease recovery will retry: step={}",
                    originallyClaimed.currentStep());
        }
    }

    @PreDestroy
    void stop() {
        accepting.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(properties.gracefulDrain().toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static String metric(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }
}
