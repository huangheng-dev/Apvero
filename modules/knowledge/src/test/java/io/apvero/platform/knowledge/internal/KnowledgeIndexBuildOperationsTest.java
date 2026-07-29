package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildOperations.ScanOutcome;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildOperationalSlice;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeIndexBuildOperationsTest {
    @Test
    void aggregatesOnlyScopedSlicesAndUsesTheOldestEligibleAge() {
        KnowledgeIndexPersistenceRepository repository =
                mock(KnowledgeIndexPersistenceRepository.class);
        WorkspaceScope first = scope("00000000-0000-0000-0000-000000000001");
        WorkspaceScope second = scope("00000000-0000-0000-0000-000000000002");
        when(repository.readBuildOperationalSlice(first))
                .thenReturn(new BuildOperationalSlice(4L, 2));
        when(repository.readBuildOperationalSlice(second))
                .thenReturn(new BuildOperationalSlice(9L, 3));
        KnowledgeIndexBuildOperations operations = new KnowledgeIndexBuildOperations(
                repository,
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC));

        operations.succeeded(operations.scan(List.of(first, second)));

        assertThat(operations.snapshot().lastScanOutcome())
                .isEqualTo(ScanOutcome.SUCCESS);
        assertThat(operations.snapshot().oldestEligibleAgeSeconds()).isEqualTo(9);
        assertThat(operations.snapshot().reconciliationCount()).isEqualTo(5);
        verify(repository).readBuildOperationalSlice(first);
        verify(repository).readBuildOperationalSlice(second);
    }

    @Test
    void failedWorkspaceScanPublishesUnknownInsteadOfMisleadingZeroAndRecovers() {
        KnowledgeIndexPersistenceRepository repository =
                mock(KnowledgeIndexPersistenceRepository.class);
        WorkspaceScope scope = scope("00000000-0000-0000-0000-000000000001");
        when(repository.readBuildOperationalSlice(scope))
                .thenReturn(new BuildOperationalSlice(null, 0))
                .thenThrow(new IllegalStateException("sensitive database detail"))
                .thenReturn(new BuildOperationalSlice(2L, 1));
        KnowledgeIndexBuildOperations operations = new KnowledgeIndexBuildOperations(
                repository,
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC));
        operations.succeeded(operations.scan(List.of(scope)));

        assertThatThrownBy(() -> operations.scan(List.of(scope)))
                .isInstanceOf(IllegalStateException.class);
        operations.failed();
        assertThat(operations.snapshot().lastScanOutcome()).isEqualTo(ScanOutcome.FAILED);
        assertThat(operations.snapshot().oldestEligibleAgeSeconds()).isNull();
        assertThat(operations.snapshot().reconciliationCount()).isNull();
        assertThat(operations.snapshot().consecutiveFailures()).isEqualTo(1);

        operations.succeeded(operations.scan(List.of(scope)));
        assertThat(operations.snapshot().lastScanOutcome()).isEqualTo(ScanOutcome.SUCCESS);
        assertThat(operations.snapshot().consecutiveFailures()).isZero();
        assertThat(operations.snapshot().oldestEligibleAgeSeconds()).isEqualTo(2);
        assertThat(operations.snapshot().reconciliationCount()).isEqualTo(1);
    }

    @Test
    void successfulAggregateReadDoesNotClearFailuresUntilTheWholeScanCommits() {
        KnowledgeIndexPersistenceRepository repository =
                mock(KnowledgeIndexPersistenceRepository.class);
        WorkspaceScope scope = scope("00000000-0000-0000-0000-000000000001");
        when(repository.readBuildOperationalSlice(scope))
                .thenReturn(new BuildOperationalSlice(3L, 1));
        KnowledgeIndexBuildOperations operations = new KnowledgeIndexBuildOperations(
                repository,
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC));

        operations.failed();
        KnowledgeIndexBuildOperations.OperationalAggregate aggregate =
                operations.scan(List.of(scope));
        operations.failed();

        assertThat(operations.snapshot().lastScanOutcome()).isEqualTo(ScanOutcome.FAILED);
        assertThat(operations.snapshot().consecutiveFailures()).isEqualTo(2);

        operations.succeeded(aggregate);
        assertThat(operations.snapshot().lastScanOutcome()).isEqualTo(ScanOutcome.SUCCESS);
        assertThat(operations.snapshot().consecutiveFailures()).isZero();
    }

    private static WorkspaceScope scope(String workspaceId) {
        return new WorkspaceScope(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                UUID.fromString(workspaceId));
    }
}
