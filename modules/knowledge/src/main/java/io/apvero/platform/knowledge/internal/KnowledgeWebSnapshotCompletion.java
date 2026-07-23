package io.apvero.platform.knowledge.internal;

import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeException.Category;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStep;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SnapshotStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceType;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeWebSnapshotCompletion {
    private final KnowledgePersistenceRepository repository;
    private final AuditEventCatalog audit;

    public KnowledgeWebSnapshotCompletion(KnowledgePersistenceRepository repository, AuditEventCatalog audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional
    public CompletionResult complete(
            WorkspaceScope scope,
            UUID jobId,
            SafeWebCapture.CapturedWebSnapshot captured) {
        return complete(scope, jobId, null, null, captured);
    }

    @Transactional
    CompletionResult complete(
            WorkspaceScope scope,
            UUID jobId,
            Long expectedVersion,
            String expectedLeaseOwner,
            SafeWebCapture.CapturedWebSnapshot captured) {
        IngestionJobRow job = repository.lockJob(scope, jobId)
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_JOB_NOT_FOUND", Category.NOT_FOUND));
        if (expectedVersion != null && (job.lockVersion() != expectedVersion
                || !java.util.Objects.equals(job.leaseOwner(), expectedLeaseOwner))) {
            throw problem("APVERO_KNOWLEDGE_JOB_LEASE_CONFLICT", Category.CONFLICT);
        }
        if (!((job.status() == JobStatus.QUEUED || job.status() == JobStatus.SNAPSHOTTING)
                && job.currentStep() == JobStep.SNAPSHOTTING)) {
            throw problem("APVERO_KNOWLEDGE_JOB_STATE_CONFLICT", Category.CONFLICT);
        }
        SourceRow source = repository.lockSource(scope, job.sourceId())
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_SOURCE_NOT_FOUND", Category.NOT_FOUND));
        if (source.status() != SourceStatus.ACTIVE) {
            throw problem("APVERO_KNOWLEDGE_SOURCE_TOMBSTONED", Category.CONFLICT);
        }
        if (source.sourceType() != SourceType.WEB
                || !URI.create(source.canonicalWebUri()).equals(captured.requestedUri())) {
            throw problem("APVERO_KNOWLEDGE_WEB_CAPTURE_MISMATCH", Category.CONFLICT);
        }
        KnowledgeCapturedSnapshot snapshot = captured.snapshot();
        SourceRevisionRow latest = repository.findLatestRevision(scope, source.id()).orElse(null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (latest != null && latest.contentDigest().equals(snapshot.contentDigest())) {
            IngestionJobRow completed = repository.completeWebJobWithoutChange(
                            scope, job.id(), job.lockVersion(), expectedLeaseOwner, latest.id(), now)
                    .orElseThrow(() -> problem("APVERO_KNOWLEDGE_JOB_CONCURRENT_MODIFICATION", Category.CONFLICT));
            appendAudit(scope.workspaceId(), "knowledge.source.web-sync-unchanged", "knowledge-ingestion-job", job.id());
            return new CompletionResult(Outcome.UNCHANGED, source, latest, completed);
        }

        int revisionNumber = source.latestRevisionNumber() + 1;
        SourceRevisionRow revision = repository.insertRevision(scope, new SourceRevisionRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), source.id(), revisionNumber,
                snapshot.contentDigest(), snapshot.mediaType(), snapshot.bytes().length,
                null, captured.captureMetadataJson(), snapshot.bytes(), SnapshotStatus.SNAPSHOTTED,
                null, null, now));
        SourceRow updatedSource = repository.updateSourceRevision(
                        scope, source.id(), source.version(), revisionNumber, revision.id(), now)
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_SOURCE_CONCURRENT_MODIFICATION", Category.CONFLICT));
        IngestionJobRow advanced = repository.advanceWebJobAfterChangedSnapshot(
                        scope, job.id(), job.lockVersion(), expectedLeaseOwner, revision.id(), now)
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_JOB_CONCURRENT_MODIFICATION", Category.CONFLICT));
        appendAudit(scope.workspaceId(), "knowledge.source.web-snapshot-captured",
                "knowledge-source-revision", revision.id());
        return new CompletionResult(Outcome.CHANGED, updatedSource, revision, advanced);
    }

    private void appendAudit(UUID workspaceId, String action, String resourceType, UUID resourceId) {
        audit.append(workspaceId, "knowledge-job-runner", action, resourceType, resourceId.toString(),
                "SUCCEEDED", null, resourceId.toString());
    }

    public enum Outcome {
        CHANGED,
        UNCHANGED
    }

    public record CompletionResult(
            Outcome outcome,
            SourceRow source,
            SourceRevisionRow revision,
            IngestionJobRow job) {}

    private static KnowledgeException problem(String code, Category category) {
        return new KnowledgeException(code, category);
    }
}
