package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildRunner.Lifecycle;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

class KnowledgeIndexBuildRunnerTest {
    @ParameterizedTest(name = "drains 100 builds across 20 workspaces at concurrency {0}")
    @ValueSource(ints = {1, 4, 8})
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void drainsTheReferenceSchedulerEnvelopeFairly(int concurrency) throws Exception {
        Fixture fixture = fixture(true, true, concurrency, Duration.ofSeconds(1));
        List<WorkspaceScope> scopes = IntStream.rangeClosed(1, 20)
                .mapToObj(number -> scope(String.format(
                        "00000000-0000-0000-0000-%012d", number)))
                .toList();
        Map<WorkspaceScope, Integer> remaining = new LinkedHashMap<>();
        scopes.forEach(scope -> remaining.put(scope, 5));
        Set<WorkspaceScope> served = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger largestClaim = new AtomicInteger();
        when(fixture.workspaces.listForBackgroundProcessing()).thenReturn(scopes);
        when(fixture.kernel.claim(
                org.mockito.ArgumentMatchers.any(WorkspaceScope.class),
                org.mockito.ArgumentMatchers.eq(fixture.owner()),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    WorkspaceScope scope = invocation.getArgument(0);
                    int limit = invocation.getArgument(2);
                    int count;
                    synchronized (remaining) {
                        count = Math.min(remaining.get(scope), limit);
                        remaining.put(scope, remaining.get(scope) - count);
                    }
                    largestClaim.accumulateAndGet(count, Math::max);
                    return IntStream.range(0, count)
                            .mapToObj(ignored ->
                                    build(BuildStatus.EMBEDDING, BuildStep.EMBEDDING))
                            .toList();
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            served.add(invocation.getArgument(0));
            executed.incrementAndGet();
            return null;
        }).when(fixture.dispatcher).execute(
                org.mockito.ArgumentMatchers.any(WorkspaceScope.class),
                org.mockito.ArgumentMatchers.any(BuildRow.class),
                org.mockito.ArgumentMatchers.eq(fixture.owner()));

        long started = System.nanoTime();
        try {
            while (executed.get() < 100) {
                fixture.runner.poll();
                await(() -> fixture.runner.inFlight() == 0);
            }

            assertThat(executed).hasValue(100);
            assertThat(remaining.values()).containsOnly(0);
            assertThat(served).containsExactlyInAnyOrderElementsOf(scopes);
            assertThat(largestClaim.get()).isLessThanOrEqualTo(concurrency);
            System.out.printf(
                    "P2.2d-5 scheduler builds=100 workspaces=20 concurrency=%d durationMs=%d%n",
                    concurrency,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } finally {
            fixture.runner.stop();
        }
    }

    @Test
    void bothGatesFailClosedBeforeResolvingTheDispatcherOrScanningWorkspaces() {
        Fixture runnerDisabled = fixture(false, true, 1, Duration.ofSeconds(1));
        try {
            runnerDisabled.runner.poll();
            assertThat(runnerDisabled.runner.lifecycle()).isEqualTo(Lifecycle.DISABLED);
            verifyNoInteractions(
                    runnerDisabled.dispatcherProvider,
                    runnerDisabled.workspaces,
                    runnerDisabled.kernel);
        } finally {
            runnerDisabled.runner.stop();
        }

        Fixture knowledgeDisabled = fixture(true, false, 1, Duration.ofSeconds(1));
        try {
            knowledgeDisabled.runner.poll();
            assertThat(knowledgeDisabled.runner.lifecycle()).isEqualTo(Lifecycle.DISABLED);
            verifyNoInteractions(
                    knowledgeDisabled.dispatcherProvider,
                    knowledgeDisabled.workspaces,
                    knowledgeDisabled.kernel);
        } finally {
            knowledgeDisabled.runner.stop();
        }
    }

    @Test
    void doesNotClaimWhenTheStepDispatcherIsUnavailable() {
        Fixture fixture = fixture(true, true, 1, Duration.ofSeconds(1));
        when(fixture.dispatcherProvider.getIfAvailable()).thenReturn(null);
        try {
            fixture.runner.poll();
            assertThat(fixture.runner.lifecycle()).isEqualTo(Lifecycle.DISABLED);
            verifyNoInteractions(fixture.workspaces, fixture.kernel);
        } finally {
            fixture.runner.stop();
        }
    }

    @Test
    void rotatesTheFirstWorkspaceAfterCapacityIsConsumed() throws Exception {
        Fixture fixture = fixture(true, true, 1, Duration.ofSeconds(1));
        WorkspaceScope first = scope("00000000-0000-0000-0000-000000000001");
        WorkspaceScope second = scope("00000000-0000-0000-0000-000000000002");
        BuildRow firstBuild = build(BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        BuildRow secondBuild = build(BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        when(fixture.workspaces.listForBackgroundProcessing())
                .thenReturn(List.of(second, first));
        when(fixture.kernel.claim(first, fixture.owner(), 1))
                .thenReturn(List.of(firstBuild));
        when(fixture.kernel.claim(second, fixture.owner(), 1))
                .thenReturn(List.of(secondBuild));
        try {
            fixture.runner.poll();
            await(() -> fixture.runner.inFlight() == 0);
            fixture.runner.poll();
            await(() -> fixture.runner.inFlight() == 0);

            InOrder order = inOrder(fixture.kernel);
            order.verify(fixture.kernel).claim(first, fixture.owner(), 1);
            order.verify(fixture.kernel).claim(second, fixture.owner(), 1);
            verify(fixture.dispatcher).execute(first, firstBuild, fixture.owner());
            verify(fixture.dispatcher).execute(second, secondBuild, fixture.owner());
            assertThat(fixture.runner.lifecycle()).isEqualTo(Lifecycle.ACCEPTING);
        } finally {
            fixture.runner.stop();
        }
    }

    @Test
    void preventsOverlappingPolls() throws Exception {
        Fixture fixture = fixture(true, true, 1, Duration.ofSeconds(1));
        CountDownLatch scanEntered = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        when(fixture.workspaces.listForBackgroundProcessing()).thenAnswer(invocation -> {
            scanEntered.countDown();
            releaseScan.await(1, TimeUnit.SECONDS);
            return List.of();
        });
        Thread firstPoll = Thread.ofPlatform().start(fixture.runner::poll);
        try {
            assertThat(scanEntered.await(1, TimeUnit.SECONDS)).isTrue();
            fixture.runner.poll();
            releaseScan.countDown();
            firstPoll.join(1_000);
            verify(fixture.workspaces).listForBackgroundProcessing();
            verifyNoInteractions(fixture.kernel);
        } finally {
            releaseScan.countDown();
            fixture.runner.stop();
        }
    }

    @Test
    void failedOperationalScanPublishesFailureAndDoesNotClaim() {
        Fixture fixture = fixture(true, true, 1, Duration.ofSeconds(1));
        WorkspaceScope scope = scope("00000000-0000-0000-0000-000000000001");
        when(fixture.workspaces.listForBackgroundProcessing()).thenReturn(List.of(scope));
        org.mockito.Mockito.doThrow(new IllegalStateException("database details"))
                .when(fixture.operations)
                .scan(List.of(scope));
        try {
            fixture.runner.poll();
            verify(fixture.operations).failed();
            verifyNoInteractions(fixture.kernel);
        } finally {
            fixture.runner.stop();
        }
    }

    @Test
    void failedClaimInvalidatesTheCompleteSnapshotWithoutLeakingTheFailure() {
        Fixture fixture = fixture(true, true, 1, Duration.ofSeconds(1));
        WorkspaceScope scope = scope("00000000-0000-0000-0000-000000000001");
        when(fixture.workspaces.listForBackgroundProcessing()).thenReturn(List.of(scope));
        when(fixture.kernel.claim(scope, fixture.owner(), 1))
                .thenThrow(new IllegalStateException("tenant and SQL detail"));
        try {
            fixture.runner.poll();
            verify(fixture.operations).scan(List.of(scope));
            verify(fixture.operations).failed();
            verify(fixture.operations, never()).succeeded(
                    org.mockito.ArgumentMatchers.any());
            verifyNoInteractions(fixture.dispatcher);
        } finally {
            fixture.runner.stop();
        }
    }

    @Test
    void boundedCapacityPreventsAdditionalClaimsUntilWorkCompletes() throws Exception {
        Fixture fixture = fixture(true, true, 2, Duration.ofSeconds(1));
        WorkspaceScope scope = scope("00000000-0000-0000-0000-000000000001");
        BuildRow first = build(BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        BuildRow second = build(BuildStatus.INDEXING, BuildStep.INDEXING);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.workspaces.listForBackgroundProcessing())
                .thenReturn(List.of(scope));
        when(fixture.kernel.claim(scope, fixture.owner(), 2))
                .thenReturn(List.of(first, second));
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            release.await(1, TimeUnit.SECONDS);
            return null;
        }).when(fixture.dispatcher).execute(
                org.mockito.ArgumentMatchers.eq(scope),
                org.mockito.ArgumentMatchers.any(BuildRow.class),
                org.mockito.ArgumentMatchers.eq(fixture.owner()));
        try {
            fixture.runner.poll();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.runner.inFlight()).isEqualTo(2);
            fixture.runner.poll();
            verify(fixture.kernel).claim(scope, fixture.owner(), 2);
            verify(fixture.kernel, never()).claim(scope, fixture.owner(), 1);
        } finally {
            release.countDown();
            await(() -> fixture.runner.inFlight() == 0);
            fixture.runner.stop();
        }
    }

    @Test
    void boundedDrainStopsClaimsWithoutInterruptingAmbiguousWork() throws Exception {
        Fixture fixture = fixture(true, true, 1, Duration.ofMillis(10));
        WorkspaceScope scope = scope("00000000-0000-0000-0000-000000000001");
        BuildRow build = build(BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(fixture.workspaces.listForBackgroundProcessing())
                .thenReturn(List.of(scope));
        when(fixture.kernel.claim(scope, fixture.owner(), 1))
                .thenReturn(List.of(build));
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(fixture.dispatcher).execute(scope, build, fixture.owner());

        fixture.runner.poll();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        fixture.runner.stop();

        assertThat(interrupted).isFalse();
        assertThat(fixture.runner.lifecycle()).isEqualTo(Lifecycle.DRAINING);
        clearInvocations(fixture.workspaces, fixture.kernel);
        fixture.runner.poll();
        verifyNoInteractions(fixture.workspaces, fixture.kernel);

        release.countDown();
        await(() -> fixture.runner.lifecycle() == Lifecycle.STOPPED);
        assertThat(interrupted).isFalse();
        fixture.runner.stop();
        assertThat(fixture.runner.lifecycle()).isEqualTo(Lifecycle.STOPPED);
    }

    @Test
    void rejectedSubmissionLeavesTheClaimForLeaseRecovery() {
        Fixture fixture = fixture(true, true, 1, Duration.ofSeconds(1));
        WorkspaceScope scope = scope("00000000-0000-0000-0000-000000000001");
        BuildRow build = build(BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        when(fixture.workspaces.listForBackgroundProcessing())
                .thenReturn(List.of(scope));
        when(fixture.kernel.claim(scope, fixture.owner(), 1)).thenAnswer(invocation -> {
            fixture.runner.stop();
            return List.of(build);
        });

        fixture.runner.poll();

        assertThat(fixture.runner.inFlight()).isZero();
        assertThat(fixture.runner.lifecycle()).isEqualTo(Lifecycle.STOPPED);
        verifyNoInteractions(fixture.dispatcher);
    }

    @Test
    void interruptedDrainPreservesTheInterruptWithoutInterruptingWork() throws Exception {
        Fixture fixture = fixture(true, true, 1, Duration.ofSeconds(1));
        WorkspaceScope scope = scope("00000000-0000-0000-0000-000000000001");
        BuildRow build = build(BuildStatus.EMBEDDING, BuildStep.EMBEDDING);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(fixture.workspaces.listForBackgroundProcessing())
                .thenReturn(List.of(scope));
        when(fixture.kernel.claim(scope, fixture.owner(), 1))
                .thenReturn(List.of(build));
        org.mockito.Mockito.doAnswer(invocation -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            release.await(1, TimeUnit.SECONDS);
            return null;
        }).when(fixture.dispatcher).execute(scope, build, fixture.owner());

        fixture.runner.poll();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.currentThread().interrupt();
        fixture.runner.stop();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();

        assertThat(fixture.runner.lifecycle()).isEqualTo(Lifecycle.DRAINING);
        assertThat(worker.get().isInterrupted()).isFalse();
        release.countDown();
        await(() -> fixture.runner.lifecycle() == Lifecycle.STOPPED);
        assertThat(worker.get().isInterrupted()).isFalse();
    }

    @Test
    void interruptedDrainReportsStoppedWhenNoLocalWorkExists() {
        Fixture fixture = fixture(true, true, 1, Duration.ofSeconds(1));

        Thread.currentThread().interrupt();
        fixture.runner.stop();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
        assertThat(fixture.runner.lifecycle()).isEqualTo(Lifecycle.STOPPED);
    }

    private static Fixture fixture(
            boolean runnerEnabled,
            boolean knowledgeEnabled,
            int concurrency,
            Duration gracefulDrain) {
        KnowledgeAvailability availability = mock(KnowledgeAvailability.class);
        WorkspaceScopeCatalog workspaces = mock(WorkspaceScopeCatalog.class);
        KnowledgeIndexBuildTransitionKernel kernel =
                mock(KnowledgeIndexBuildTransitionKernel.class);
        KnowledgeIndexBuildStepDispatcher dispatcher =
                mock(KnowledgeIndexBuildStepDispatcher.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeIndexBuildStepDispatcher> provider =
                mock(ObjectProvider.class);
        KnowledgeIndexBuildOperations operations =
                mock(KnowledgeIndexBuildOperations.class);
        KnowledgeIndexBuildTelemetry telemetry =
                mock(KnowledgeIndexBuildTelemetry.class);
        when(availability.isEnabled()).thenReturn(knowledgeEnabled);
        when(provider.getIfAvailable()).thenReturn(dispatcher);
        KnowledgeIndexBuildRunner runner = new KnowledgeIndexBuildRunner(
                availability,
                workspaces,
                kernel,
                provider,
                KnowledgeIndexBuildStepDispatcherTest.properties(
                        runnerEnabled,
                        concurrency,
                        Duration.ofSeconds(30),
                        gracefulDrain),
                operations,
                telemetry);
        return new Fixture(
                runner, workspaces, kernel, dispatcher, provider, operations, telemetry);
    }

    private static BuildRow build(BuildStatus status, BuildStep step) {
        BuildRow build = mock(BuildRow.class);
        when(build.status()).thenReturn(status);
        when(build.currentStep()).thenReturn(step);
        return build;
    }

    private static WorkspaceScope scope(String workspaceId) {
        return new WorkspaceScope(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                UUID.fromString(workspaceId));
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!check.complete() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(check.complete()).isTrue();
    }

    @FunctionalInterface
    private interface Check {
        boolean complete();
    }

    private record Fixture(
            KnowledgeIndexBuildRunner runner,
            WorkspaceScopeCatalog workspaces,
            KnowledgeIndexBuildTransitionKernel kernel,
            KnowledgeIndexBuildStepDispatcher dispatcher,
            ObjectProvider<KnowledgeIndexBuildStepDispatcher> dispatcherProvider,
            KnowledgeIndexBuildOperations operations,
            KnowledgeIndexBuildTelemetry telemetry) {
        String owner() {
            return runner.leaseOwner();
        }
    }
}
