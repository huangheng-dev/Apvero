package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildOperations.OperationalAggregate;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component("knowledgeIndexBuildRunnerWorker")
final class KnowledgeIndexBuildRunner {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(KnowledgeIndexBuildRunner.class);
    private static final Comparator<WorkspaceScope> SCOPE_ORDER =
            Comparator.comparing(WorkspaceScope::workspaceId)
                    .thenComparing(WorkspaceScope::tenantId);

    private final KnowledgeAvailability availability;
    private final WorkspaceScopeCatalog workspaces;
    private final KnowledgeIndexBuildTransitionKernel kernel;
    private final ObjectProvider<KnowledgeIndexBuildStepDispatcher> dispatcherProvider;
    private final KnowledgeIndexBuildRunnerProperties properties;
    private final KnowledgeIndexBuildOperations operations;
    private final KnowledgeIndexBuildTelemetry telemetry;
    private final String leaseOwner = "index-build-runner-" + UUID.randomUUID();
    private final AtomicBoolean pollActive = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger workspaceCursor = new AtomicInteger();
    private final AtomicReference<Lifecycle> lifecycle =
            new AtomicReference<>(Lifecycle.DISABLED);
    private final ThreadPoolExecutor executor;

    KnowledgeIndexBuildRunner(
            KnowledgeAvailability availability,
            WorkspaceScopeCatalog workspaces,
            KnowledgeIndexBuildTransitionKernel kernel,
            ObjectProvider<KnowledgeIndexBuildStepDispatcher> dispatcherProvider,
            KnowledgeIndexBuildRunnerProperties properties,
            KnowledgeIndexBuildOperations operations,
            KnowledgeIndexBuildTelemetry telemetry) {
        this.availability = availability;
        this.workspaces = workspaces;
        this.kernel = kernel;
        this.dispatcherProvider = dispatcherProvider;
        this.properties = properties;
        this.operations = operations;
        this.telemetry = telemetry;
        this.executor = new ThreadPoolExecutor(
                properties.concurrency(),
                properties.concurrency(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.concurrency()),
                Thread.ofPlatform()
                        .daemon(true)
                        .name("apvero-knowledge-index-build-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
        telemetry.bindGauges(inFlight::get, operations::snapshot);
    }

    @Scheduled(
            fixedDelayString =
                    "${apvero.knowledge.index-build-runner.poll-interval:1s}")
    void poll() {
        if (!gatesEnabled()) {
            lifecycle.compareAndSet(Lifecycle.ACCEPTING, Lifecycle.DISABLED);
            operations.disabled();
            return;
        }
        if (!pollActive.compareAndSet(false, true)) {
            return;
        }
        try {
            KnowledgeIndexBuildStepDispatcher dispatcher =
                    dispatcherProvider.getIfAvailable();
            if (dispatcher == null) {
                lifecycle.set(Lifecycle.DISABLED);
                operations.failed();
                return;
            }
            lifecycle.compareAndSet(Lifecycle.DISABLED, Lifecycle.ACCEPTING);
            List<WorkspaceScope> scopes;
            OperationalAggregate aggregate;
            try {
                scopes = new ArrayList<>(workspaces.listForBackgroundProcessing());
                scopes.sort(SCOPE_ORDER);
                aggregate = operations.scan(scopes);
            } catch (RuntimeException failure) {
                operations.failed();
                LOGGER.warn(
                        "Knowledge index build scan failed: code={}",
                        "APVERO_KNOWLEDGE_INDEX_BUILD_SCAN_FAILED");
                return;
            }
            if (scopes.isEmpty()) {
                workspaceCursor.set(0);
                operations.succeeded(aggregate);
                return;
            }
            int capacity = properties.concurrency() - inFlight.get();
            if (capacity <= 0) {
                operations.succeeded(aggregate);
                return;
            }
            int start = Math.floorMod(workspaceCursor.get(), scopes.size());
            int visited = 0;
            while (visited < scopes.size() && capacity > 0 && gatesEnabled()) {
                WorkspaceScope scope = scopes.get((start + visited) % scopes.size());
                List<BuildRow> claimed;
                try {
                    claimed = kernel.claim(
                            scope,
                            leaseOwner,
                            Math.min(capacity, properties.claimBatch()));
                } catch (RuntimeException failure) {
                    operations.failed();
                    LOGGER.warn(
                            "Knowledge index build claim failed: code={}",
                            "APVERO_KNOWLEDGE_INDEX_BUILD_CLAIM_FAILED");
                    rotateCursor(start, visited, scopes.size());
                    return;
                }
                visited++;
                for (BuildRow build : claimed) {
                    telemetry.claimed(build.currentStep());
                    telemetry.attempt(build.currentStep(), build.attemptCount());
                    if (capacity <= 0 || !submit(dispatcher, scope, build)) {
                        operations.failed();
                        rotateCursor(start, visited, scopes.size());
                        return;
                    }
                    capacity--;
                }
            }
            rotateCursor(start, visited, scopes.size());
            operations.succeeded(aggregate);
        } finally {
            pollActive.set(false);
        }
    }

    private boolean submit(
            KnowledgeIndexBuildStepDispatcher dispatcher,
            WorkspaceScope scope,
            BuildRow build) {
        inFlight.incrementAndGet();
        long queuedAt = System.nanoTime();
        try {
            executor.execute(() -> run(dispatcher, scope, build, queuedAt));
            return true;
        } catch (RejectedExecutionException exception) {
            inFlight.decrementAndGet();
            return false;
        }
    }

    private void run(
            KnowledgeIndexBuildStepDispatcher dispatcher,
            WorkspaceScope scope,
            BuildRow claimed,
            long queuedAt) {
        telemetry.queueWait(
                claimed.currentStep(), System.nanoTime() - queuedAt);
        try {
            dispatcher.execute(scope, claimed, leaseOwner);
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Knowledge index build step failed: step={}, code={}",
                    claimed.currentStep(),
                    "APVERO_KNOWLEDGE_INDEX_BUILD_STEP_FAILED");
        } finally {
            int remaining = inFlight.decrementAndGet();
            if (remaining == 0 && executor.isShutdown()) {
                lifecycle.set(Lifecycle.STOPPED);
            }
        }
    }

    private boolean gatesEnabled() {
        Lifecycle current = lifecycle.get();
        return current != Lifecycle.DRAINING
                && current != Lifecycle.STOPPED
                && availability.isEnabled()
                && properties.enabled();
    }

    private void rotateCursor(int start, int visited, int size) {
        if (visited > 0) {
            workspaceCursor.set((start + visited) % size);
        }
    }

    @PreDestroy
    void stop() {
        Lifecycle previous = lifecycle.getAndUpdate(current ->
                current == Lifecycle.STOPPED ? Lifecycle.STOPPED : Lifecycle.DRAINING);
        if (previous == Lifecycle.STOPPED) {
            return;
        }
        executor.shutdown();
        try {
            if (executor.awaitTermination(
                    properties.gracefulDrain().toMillis(), TimeUnit.MILLISECONDS)) {
                lifecycle.set(Lifecycle.STOPPED);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (executor.isTerminated()) {
                lifecycle.set(Lifecycle.STOPPED);
            }
        }
    }

    Lifecycle lifecycle() {
        if (!availability.isEnabled() || !properties.enabled()) {
            return lifecycle.get() == Lifecycle.STOPPED
                    ? Lifecycle.STOPPED
                    : Lifecycle.DISABLED;
        }
        return lifecycle.get();
    }

    int inFlight() {
        return inFlight.get();
    }

    String leaseOwner() {
        return leaseOwner;
    }

    enum Lifecycle {
        DISABLED,
        ACCEPTING,
        DRAINING,
        STOPPED
    }
}
