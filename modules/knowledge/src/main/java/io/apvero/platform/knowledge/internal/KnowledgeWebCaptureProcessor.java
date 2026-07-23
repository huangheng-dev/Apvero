package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeException.Category;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceType;
import java.net.URI;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeWebCaptureProcessor {
    private final KnowledgeAvailability availability;
    private final WorkspaceScopeCatalog workspaces;
    private final KnowledgePersistenceRepository repository;
    private final SafeWebCapture capture;
    private final KnowledgeWebSnapshotCompletion completion;

    KnowledgeWebCaptureProcessor(
            KnowledgeAvailability availability,
            WorkspaceScopeCatalog workspaces,
            KnowledgePersistenceRepository repository,
            SafeWebCapture capture,
            KnowledgeWebSnapshotCompletion completion) {
        this.availability = availability;
        this.workspaces = workspaces;
        this.repository = repository;
        this.capture = capture;
        this.completion = completion;
    }

    KnowledgeWebSnapshotCompletion.CompletionResult process(UUID workspaceId, UUID jobId) {
        availability.requireEnabled();
        if (workspaceId == null || jobId == null) {
            throw problem("APVERO_KNOWLEDGE_IDENTIFIER_INVALID", Category.BAD_REQUEST);
        }
        WorkspaceScope scope = workspaces.require(workspaceId);
        IngestionJobRow job = repository.findJob(scope, jobId)
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_JOB_NOT_FOUND", Category.NOT_FOUND));
        SourceRow source = repository.findSource(scope, job.sourceId())
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_SOURCE_NOT_FOUND", Category.NOT_FOUND));
        if (source.sourceType() != SourceType.WEB || source.canonicalWebUri() == null) {
            throw problem("APVERO_KNOWLEDGE_SOURCE_TYPE_CONFLICT", Category.CONFLICT);
        }
        SafeWebCapture.CapturedWebSnapshot captured = capture.capture(URI.create(source.canonicalWebUri()));
        return completion.complete(scope, jobId, captured);
    }

    KnowledgeWebSnapshotCompletion.CompletionResult process(
            WorkspaceScope scope, IngestionJobRow job, String leaseOwner) {
        availability.requireEnabled();
        SourceRow source = repository.findSource(scope, job.sourceId())
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_SOURCE_NOT_FOUND", Category.NOT_FOUND));
        if (source.sourceType() != SourceType.WEB || source.canonicalWebUri() == null) {
            throw problem("APVERO_KNOWLEDGE_SOURCE_TYPE_CONFLICT", Category.CONFLICT);
        }
        SafeWebCapture.CapturedWebSnapshot captured = capture.capture(URI.create(source.canonicalWebUri()));
        return completion.complete(scope, job.id(), job.lockVersion(), leaseOwner, captured);
    }

    private static KnowledgeException problem(String code, Category category) {
        return new KnowledgeException(code, category);
    }
}
