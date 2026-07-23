package io.apvero.platform.knowledge.internal;

import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ErrorCategory;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeJobLeaseService {
    private final KnowledgePersistenceRepository repository;
    private final KnowledgeRunnerProperties properties;
    private final KnowledgeBackoffPolicy backoff;
    private final AuditEventCatalog audit;

    public KnowledgeJobLeaseService(
            KnowledgePersistenceRepository repository,
            KnowledgeRunnerProperties properties,
            KnowledgeBackoffPolicy backoff,
            AuditEventCatalog audit) {
        this.repository = repository;
        this.properties = properties;
        this.backoff = backoff;
        this.audit = audit;
    }

    @Transactional
    public List<IngestionJobRow> claim(WorkspaceScope scope, String leaseOwner, int capacity) {
        OffsetDateTime now = now();
        for (IngestionJobRow exhausted : repository.failExpiredExhaustedJobs(scope, now)) {
            appendAudit(scope, "knowledge.ingestion.retry-exhausted", "FAILED", exhausted);
        }
        return repository.claimJobs(scope, leaseOwner, now, now.plus(properties.leaseDuration()),
                Math.min(capacity, properties.claimBatch()));
    }

    @Transactional
    public IngestionJobRow advanceToChunking(
            WorkspaceScope scope, IngestionJobRow job, String leaseOwner) {
        return repository.advanceJobToChunking(scope, job.id(), job.lockVersion(), leaseOwner, now())
                .orElseThrow(KnowledgeJobLeaseService::leaseConflict);
    }

    @Transactional
    public void fail(
            WorkspaceScope scope,
            IngestionJobRow job,
            String leaseOwner,
            KnowledgeJobFailure failure) {
        OffsetDateTime failedAt = now();
        boolean retryWait = failure.retryable() && job.attemptCount() < job.maximumAttempts();
        JobStatus status = retryWait ? JobStatus.RETRY_WAIT : JobStatus.FAILED;
        OffsetDateTime nextAttemptAt = retryWait
                ? failedAt.plus(backoff.delay(job.id(), job.attemptCount())) : null;
        IngestionJobRow failed = repository.failJob(
                        scope, job.id(), job.lockVersion(), leaseOwner, status, failure.retryable(),
                        nextAttemptAt, failure.code(), failure.category(), failedAt)
                .orElseThrow(KnowledgeJobLeaseService::leaseConflict);
        appendAudit(scope, retryWait ? "knowledge.ingestion.retry-scheduled" : "knowledge.ingestion.failed",
                retryWait ? "SUCCEEDED" : "FAILED", failed);
    }

    private void appendAudit(WorkspaceScope scope, String action, String outcome, IngestionJobRow job) {
        audit.append(scope.workspaceId(), "knowledge-job-runner", action, "knowledge-ingestion-job",
                job.id().toString(), outcome, null, job.id().toString());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static KnowledgeException leaseConflict() {
        return new KnowledgeException(
                "APVERO_KNOWLEDGE_JOB_LEASE_CONFLICT", KnowledgeException.Category.CONFLICT);
    }

    record KnowledgeJobFailure(String code, ErrorCategory category, boolean retryable) {}
}
