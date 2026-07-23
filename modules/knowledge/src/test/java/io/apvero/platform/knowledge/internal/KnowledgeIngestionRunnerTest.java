package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobKind;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStep;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SyncOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class KnowledgeIngestionRunnerTest {
    @Test
    void shutdownStopsClaimsAndInterruptsWorkAfterTheBoundedDrain() throws Exception {
        KnowledgeAvailability availability = mock(KnowledgeAvailability.class);
        WorkspaceScopeCatalog workspaces = mock(WorkspaceScopeCatalog.class);
        KnowledgePersistenceRepository repository = mock(KnowledgePersistenceRepository.class);
        KnowledgeJobLeaseService leases = mock(KnowledgeJobLeaseService.class);
        KnowledgeIngestionStepExecutor steps = mock(KnowledgeIngestionStepExecutor.class);
        WorkspaceScope scope = new WorkspaceScope(UUID.randomUUID(), UUID.randomUUID());
        IngestionJobRow job = job(scope);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        when(availability.isEnabled()).thenReturn(true);
        when(workspaces.listForBackgroundProcessing()).thenReturn(List.of(scope));
        when(leases.claim(any(), anyString(), anyInt())).thenReturn(List.of(job));
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
                throw new IllegalStateException("interrupted", exception);
            }
            return null;
        }).when(steps).execute(any(), any(), anyString());
        when(steps.classify(any())).thenReturn(new KnowledgeJobLeaseService.KnowledgeJobFailure(
                "APVERO_KNOWLEDGE_INGESTION_INTERNAL",
                KnowledgePersistenceRecords.ErrorCategory.INTERNAL, true));
        when(repository.findJob(scope, job.id())).thenReturn(java.util.Optional.empty());

        KnowledgeRunnerProperties properties = new KnowledgeRunnerProperties(
                true, 1, 1, Duration.ofSeconds(60), Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ofMinutes(5), Duration.ofMillis(10));
        KnowledgeIngestionRunner runner = new KnowledgeIngestionRunner(
                availability, workspaces, repository, leases, steps, properties, new SimpleMeterRegistry());

        runner.poll();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        runner.stop();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();

        org.mockito.Mockito.clearInvocations(workspaces);
        runner.poll();
        verifyNoInteractions(workspaces);
    }

    private static IngestionJobRow job(WorkspaceScope scope) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID jobId = UUID.randomUUID();
        return new IngestionJobRow(
                jobId, scope.tenantId(), scope.workspaceId(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), JobKind.CREATE_SOURCE, JobStatus.PARSING, JobStep.PARSING,
                SyncOutcome.CHANGED, 1, 3, null, "runner-test", now.plusMinutes(1), 2,
                "test:" + jobId, false, null, null, "{}", false, now, null, now, now);
    }
}
