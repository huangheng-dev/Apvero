package io.apvero.platform.knowledge.internal;

import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeIngestionJob;
import io.apvero.platform.knowledge.KnowledgeIngestionJobCatalog;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultKnowledgeIngestionJobCatalog implements KnowledgeIngestionJobCatalog {
    private final KnowledgeAvailability availability;
    private final WorkspaceScopeCatalog workspaces;
    private final KnowledgePersistenceRepository repository;
    private final AuditEventCatalog audit;

    DefaultKnowledgeIngestionJobCatalog(
            KnowledgeAvailability availability,
            WorkspaceScopeCatalog workspaces,
            KnowledgePersistenceRepository repository,
            AuditEventCatalog audit) {
        this.availability = availability;
        this.workspaces = workspaces;
        this.repository = repository;
        this.audit = audit;
    }

    @Override
    public List<KnowledgeIngestionJob> list(
            UUID workspaceId, UUID knowledgeBaseId, KnowledgeIngestionJob.Status status) {
        WorkspaceScope scope = scope(workspaceId);
        JobStatus internalStatus = status == null ? null : JobStatus.valueOf(status.name());
        return repository.listJobs(scope, knowledgeBaseId, internalStatus).stream()
                .map(DefaultKnowledgeIngestionJobCatalog::map)
                .toList();
    }

    @Override
    public KnowledgeIngestionJob get(UUID workspaceId, UUID jobId) {
        return map(requireJob(scope(workspaceId), required(jobId)));
    }

    @Override
    @Transactional
    public KnowledgeIngestionJob retry(
            UUID workspaceId, UUID jobId, KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        IngestionJobRow job = repository.lockJob(scope, required(jobId))
                .orElseThrow(DefaultKnowledgeIngestionJobCatalog::notFound);
        if (job.status() != JobStatus.FAILED || !job.retryable()) {
            throw problem("APVERO_KNOWLEDGE_JOB_NOT_RETRYABLE", KnowledgeException.Category.CONFLICT);
        }
        IngestionJobRow retried = repository.retryFailedJob(scope, job.id(), job.lockVersion(), now())
                .orElseThrow(() -> problem(
                        "APVERO_KNOWLEDGE_JOB_CONCURRENT_MODIFICATION", KnowledgeException.Category.CONFLICT));
        appendAudit(scope, context, "knowledge.ingestion.retry-requested", retried.id());
        return map(retried);
    }

    @Override
    @Transactional
    public KnowledgeIngestionJob cancel(
            UUID workspaceId, UUID jobId, KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        IngestionJobRow job = repository.lockJob(scope, required(jobId))
                .orElseThrow(DefaultKnowledgeIngestionJobCatalog::notFound);
        if (job.status() != JobStatus.QUEUED && job.status() != JobStatus.RETRY_WAIT) {
            throw problem("APVERO_KNOWLEDGE_JOB_NOT_CANCELLABLE", KnowledgeException.Category.CONFLICT);
        }
        IngestionJobRow cancelled = repository.cancelWaitingJob(scope, job.id(), job.lockVersion(), now())
                .orElseThrow(() -> problem(
                        "APVERO_KNOWLEDGE_JOB_CONCURRENT_MODIFICATION", KnowledgeException.Category.CONFLICT));
        appendAudit(scope, context, "knowledge.ingestion.cancelled", cancelled.id());
        return map(cancelled);
    }

    private WorkspaceScope scope(UUID workspaceId) {
        availability.requireEnabled();
        return workspaces.require(required(workspaceId));
    }

    private IngestionJobRow requireJob(WorkspaceScope scope, UUID jobId) {
        return repository.findJob(scope, jobId).orElseThrow(DefaultKnowledgeIngestionJobCatalog::notFound);
    }

    private void appendAudit(
            WorkspaceScope scope, KnowledgeCommandContext context, String action, UUID jobId) {
        String actor = context == null || context.actorId() == null || context.actorId().isBlank()
                ? "system" : bounded(context.actorId(), 160);
        String trace = context == null || context.traceId() == null || context.traceId().isBlank()
                ? UUID.randomUUID().toString() : bounded(context.traceId(), 80);
        String sourceIp = context == null || context.sourceIp() == null || context.sourceIp().isBlank()
                ? null : bounded(context.sourceIp(), 64);
        audit.append(scope.workspaceId(), actor, action, "knowledge-ingestion-job", jobId.toString(),
                "SUCCEEDED", sourceIp, trace);
    }

    private static KnowledgeIngestionJob map(IngestionJobRow row) {
        return new KnowledgeIngestionJob(
                row.id(), row.tenantId(), row.workspaceId(), row.knowledgeBaseId(), row.sourceId(),
                row.sourceRevisionId(), KnowledgeIngestionJob.Status.valueOf(row.status().name()),
                KnowledgeIngestionJob.Step.valueOf(row.currentStep().name()), row.attemptCount(), row.retryable(),
                row.syncOutcome() == null ? null : KnowledgeIngestionJob.SyncOutcome.valueOf(row.syncOutcome().name()),
                row.nextAttemptAt(), row.errorCode(), row.createdAt(), row.startedAt(), row.completedAt(), row.updatedAt());
    }

    private static UUID required(UUID value) {
        if (value == null) {
            throw problem("APVERO_KNOWLEDGE_IDENTIFIER_INVALID", KnowledgeException.Category.BAD_REQUEST);
        }
        return value;
    }

    private static String bounded(String value, int maximum) {
        String normalized = value.trim();
        return normalized.codePointCount(0, normalized.length()) <= maximum
                ? normalized : normalized.substring(0, normalized.offsetByCodePoints(0, maximum));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static KnowledgeException notFound() {
        return problem("APVERO_KNOWLEDGE_JOB_NOT_FOUND", KnowledgeException.Category.NOT_FOUND);
    }

    private static KnowledgeException problem(String code, KnowledgeException.Category category) {
        return new KnowledgeException(code, category);
    }
}
