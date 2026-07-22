package io.apvero.platform.knowledge.internal;

import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.AddInlineKnowledgeSourceRevisionCommand;
import io.apvero.platform.knowledge.AddUploadedKnowledgeSourceRevisionCommand;
import io.apvero.platform.knowledge.CreateInlineKnowledgeSourceCommand;
import io.apvero.platform.knowledge.CreateKnowledgeBaseCommand;
import io.apvero.platform.knowledge.CreateUploadedKnowledgeSourceCommand;
import io.apvero.platform.knowledge.CreateWebKnowledgeSourceCommand;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.KnowledgeBase;
import io.apvero.platform.knowledge.KnowledgeBaseCatalog;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeException.Category;
import io.apvero.platform.knowledge.KnowledgeIngestionJob;
import io.apvero.platform.knowledge.KnowledgeSource;
import io.apvero.platform.knowledge.KnowledgeSourceCatalog;
import io.apvero.platform.knowledge.KnowledgeSourceRevision;
import io.apvero.platform.knowledge.KnowledgeSourceSnapshot;
import io.apvero.platform.knowledge.SourceIngestionReceipt;
import io.apvero.platform.knowledge.SourceRevisionReceipt;
import io.apvero.platform.knowledge.SourceSyncReceipt;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobKind;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStep;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SnapshotStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceType;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SyncOutcome;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultKnowledgeCatalog implements KnowledgeBaseCatalog, KnowledgeSourceCatalog {
    private static final int MAXIMUM_ATTEMPTS = 3;
    private static final String EMPTY_JSON = "{}";

    private final KnowledgeAvailability availability;
    private final WorkspaceScopeCatalog workspaces;
    private final KnowledgePersistenceRepository repository;
    private final KnowledgeSourceCapture capture;
    private final AuditEventCatalog audit;

    public DefaultKnowledgeCatalog(
            KnowledgeAvailability availability,
            WorkspaceScopeCatalog workspaces,
            KnowledgePersistenceRepository repository,
            KnowledgeSourceCapture capture,
            AuditEventCatalog audit) {
        this.availability = availability;
        this.workspaces = workspaces;
        this.repository = repository;
        this.capture = capture;
        this.audit = audit;
    }

    @Override
    public List<KnowledgeBase> list(UUID workspaceId) {
        WorkspaceScope scope = scope(workspaceId);
        return repository.listBases(scope).stream().map(this::mapBase).toList();
    }

    @Override
    @Transactional
    public KnowledgeBase create(
            UUID workspaceId,
            CreateKnowledgeBaseCommand command,
            KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        if (command == null) {
            throw problem("APVERO_KNOWLEDGE_REQUEST_INVALID", Category.BAD_REQUEST);
        }
        String slug = requireSlug(command.slug());
        String name = requireName(command.name());
        String description = requireDescription(command.description());
        if (repository.findBaseBySlug(scope, slug).isPresent()) {
            throw problem("APVERO_KNOWLEDGE_BASE_SLUG_CONFLICT", Category.CONFLICT);
        }
        OffsetDateTime now = now();
        BaseRow created = repository.insertBase(scope, new BaseRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), slug, name, description,
                BaseStatus.ACTIVE, 1, now, now));
        appendAudit(workspaceId, context, "knowledge.base.created", "knowledge-base", created.id());
        return mapBase(created);
    }

    @Override
    public List<KnowledgeSource> listSources(UUID workspaceId, UUID knowledgeBaseId) {
        WorkspaceScope scope = scope(workspaceId);
        requireBase(scope, knowledgeBaseId);
        return repository.listSources(scope, knowledgeBaseId).stream().map(this::mapSource).toList();
    }

    @Override
    public List<KnowledgeSourceRevision> listRevisions(UUID workspaceId, UUID sourceId) {
        WorkspaceScope scope = scope(workspaceId);
        requireSource(scope, sourceId);
        return repository.listRevisions(scope, sourceId).stream().map(this::mapRevision).toList();
    }

    @Override
    @Transactional
    public SourceIngestionReceipt createInline(
            UUID workspaceId,
            UUID knowledgeBaseId,
            CreateInlineKnowledgeSourceCommand command,
            KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        requireBase(scope, knowledgeBaseId);
        if (command == null) {
            throw problem("APVERO_KNOWLEDGE_REQUEST_INVALID", Category.BAD_REQUEST);
        }
        KnowledgeCapturedSnapshot snapshot = capture.inline(command.sourceType(), command.content());
        return createSource(scope, knowledgeBaseId, requireName(command.name()), snapshot, context);
    }

    @Override
    @Transactional
    public SourceIngestionReceipt createUpload(
            UUID workspaceId,
            UUID knowledgeBaseId,
            CreateUploadedKnowledgeSourceCommand command,
            KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        requireBase(scope, knowledgeBaseId);
        if (command == null) {
            throw problem("APVERO_KNOWLEDGE_REQUEST_INVALID", Category.BAD_REQUEST);
        }
        KnowledgeCapturedSnapshot snapshot = capture.upload(
                command.originalFilename(), command.declaredMediaType(), command.declaredSize(), command.content());
        return createSource(scope, knowledgeBaseId, requireName(command.name()), snapshot, context);
    }

    @Override
    @Transactional
    public SourceIngestionReceipt createWeb(
            UUID workspaceId,
            UUID knowledgeBaseId,
            CreateWebKnowledgeSourceCommand command,
            KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        requireBase(scope, knowledgeBaseId);
        if (command == null) {
            throw problem("APVERO_KNOWLEDGE_REQUEST_INVALID", Category.BAD_REQUEST);
        }
        String canonicalUri = SafeWebCapture.canonicalize(command.url()).toASCIIString();
        OffsetDateTime now = now();
        SourceRow source = repository.insertSource(scope, new SourceRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), knowledgeBaseId,
                requireName(command.name()), SourceType.WEB, SourceStatus.ACTIVE, canonicalUri,
                0, null, 1, null, null, now, now));
        IngestionJobRow job = insertSnapshotJob(scope, source, JobKind.CREATE_SOURCE, now);
        appendAudit(workspaceId, context, "knowledge.source.created", "knowledge-source", source.id());
        appendAudit(workspaceId, context, "knowledge.source.web-sync-requested", "knowledge-ingestion-job", job.id());
        return new SourceIngestionReceipt(mapSource(source), null, mapJob(job));
    }

    @Override
    @Transactional
    public SourceRevisionReceipt addInlineRevision(
            UUID workspaceId,
            UUID sourceId,
            AddInlineKnowledgeSourceRevisionCommand command,
            KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        SourceRow source = lockActiveSource(scope, sourceId);
        KnowledgeSource.Type type = publicType(source.sourceType());
        if (type != KnowledgeSource.Type.TEXT && type != KnowledgeSource.Type.MARKDOWN) {
            throw problem("APVERO_KNOWLEDGE_SOURCE_TYPE_CONFLICT", Category.CONFLICT);
        }
        if (command == null) {
            throw problem("APVERO_KNOWLEDGE_REQUEST_INVALID", Category.BAD_REQUEST);
        }
        return addRevision(scope, source, capture.inline(type, command.content()), context);
    }

    @Override
    @Transactional
    public SourceRevisionReceipt addUploadRevision(
            UUID workspaceId,
            UUID sourceId,
            AddUploadedKnowledgeSourceRevisionCommand command,
            KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        SourceRow source = lockActiveSource(scope, sourceId);
        if (source.sourceType() == SourceType.WEB) {
            throw problem("APVERO_KNOWLEDGE_SOURCE_TYPE_CONFLICT", Category.CONFLICT);
        }
        if (command == null) {
            throw problem("APVERO_KNOWLEDGE_REQUEST_INVALID", Category.BAD_REQUEST);
        }
        KnowledgeCapturedSnapshot snapshot = capture.upload(
                command.originalFilename(), command.declaredMediaType(), command.declaredSize(), command.content());
        if (snapshot.sourceType() != publicType(source.sourceType())) {
            throw problem("APVERO_KNOWLEDGE_SOURCE_TYPE_CONFLICT", Category.CONFLICT);
        }
        return addRevision(scope, source, snapshot, context);
    }

    @Override
    @Transactional
    public SourceSyncReceipt synchronizeWeb(
            UUID workspaceId,
            UUID sourceId,
            KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        SourceRow source = lockActiveSource(scope, sourceId);
        if (source.sourceType() != SourceType.WEB) {
            throw problem("APVERO_KNOWLEDGE_SOURCE_TYPE_CONFLICT", Category.CONFLICT);
        }
        if (repository.hasActiveWebSnapshotJob(scope, source.id())) {
            throw problem("APVERO_KNOWLEDGE_WEB_SYNC_ALREADY_ACTIVE", Category.CONFLICT);
        }
        IngestionJobRow job = insertSnapshotJob(scope, source, JobKind.SYNCHRONIZE_SOURCE, now());
        appendAudit(workspaceId, context, "knowledge.source.web-sync-requested", "knowledge-ingestion-job", job.id());
        return new SourceSyncReceipt(SourceSyncReceipt.Outcome.SCHEDULED, mapSource(source), mapJob(job));
    }

    @Override
    public KnowledgeSourceSnapshot readRevisionContent(UUID workspaceId, UUID revisionId) {
        WorkspaceScope scope = scope(workspaceId);
        SourceRevisionRow revision = repository.findRevision(scope, requiredId(revisionId))
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_REVISION_NOT_FOUND", Category.NOT_FOUND));
        if (revision.snapshotStatus() != SnapshotStatus.SNAPSHOTTED || revision.snapshotBytes() == null) {
            throw problem("APVERO_KNOWLEDGE_SNAPSHOT_NOT_AVAILABLE", Category.CONFLICT);
        }
        String filename = revision.originalFilename() == null
                ? "source-" + revision.id() + extensionFor(revision.mediaType())
                : revision.originalFilename();
        return new KnowledgeSourceSnapshot(
                revision.contentDigest(), revision.mediaType(), filename, revision.snapshotBytes());
    }

    @Override
    @Transactional
    public void tombstone(UUID workspaceId, UUID sourceId, KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        SourceRow source = repository.lockSource(scope, requiredId(sourceId))
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_SOURCE_NOT_FOUND", Category.NOT_FOUND));
        if (source.status() == SourceStatus.TOMBSTONED) {
            return;
        }
        OffsetDateTime tombstonedAt = now();
        String actor = actor(context);
        repository.tombstoneSource(scope, source.id(), source.version(), tombstonedAt, actor)
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_SOURCE_CONCURRENT_MODIFICATION", Category.CONFLICT));
        appendAudit(workspaceId, context, "knowledge.source.tombstoned", "knowledge-source", source.id());
    }

    private SourceIngestionReceipt createSource(
            WorkspaceScope scope,
            UUID knowledgeBaseId,
            String name,
            KnowledgeCapturedSnapshot snapshot,
            KnowledgeCommandContext context) {
        OffsetDateTime now = now();
        UUID sourceId = UUID.randomUUID();
        SourceRow initialSource = repository.insertSource(scope, new SourceRow(
                sourceId, scope.tenantId(), scope.workspaceId(), knowledgeBaseId, name,
                SourceType.valueOf(snapshot.sourceType().name()), SourceStatus.ACTIVE, null,
                0, null, 1, null, null, now, now));
        SourceRevisionRow revision = insertRevision(scope, initialSource, snapshot, 1, now);
        SourceRow source = repository.updateSourceRevision(
                        scope, sourceId, initialSource.version(), 1, revision.id(), now)
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_SOURCE_CONCURRENT_MODIFICATION", Category.CONFLICT));
        IngestionJobRow job = insertJob(scope, source, revision, JobKind.CREATE_SOURCE, now);
        appendAudit(scope.workspaceId(), context, "knowledge.source.created", "knowledge-source", source.id());
        appendAudit(scope.workspaceId(), context, "knowledge.source.revision.accepted",
                "knowledge-source-revision", revision.id());
        return new SourceIngestionReceipt(mapSource(source), mapRevision(revision), mapJob(job));
    }

    private SourceRevisionReceipt addRevision(
            WorkspaceScope scope,
            SourceRow source,
            KnowledgeCapturedSnapshot snapshot,
            KnowledgeCommandContext context) {
        SourceRevisionRow latest = repository.findLatestRevision(scope, source.id())
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_REVISION_NOT_FOUND", Category.NOT_FOUND));
        if (latest.contentDigest().equals(snapshot.contentDigest())) {
            appendAudit(scope.workspaceId(), context, "knowledge.source.revision.unchanged",
                    "knowledge-source", source.id());
            return new SourceRevisionReceipt(
                    SourceRevisionReceipt.Outcome.UNCHANGED, mapSource(source), null, null);
        }
        OffsetDateTime now = now();
        int revisionNumber = source.latestRevisionNumber() + 1;
        SourceRevisionRow revision = insertRevision(scope, source, snapshot, revisionNumber, now);
        SourceRow updated = repository.updateSourceRevision(
                        scope, source.id(), source.version(), revisionNumber, revision.id(), now)
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_SOURCE_CONCURRENT_MODIFICATION", Category.CONFLICT));
        IngestionJobRow job = insertJob(scope, updated, revision, JobKind.ADD_REVISION, now);
        appendAudit(scope.workspaceId(), context, "knowledge.source.revision.accepted",
                "knowledge-source-revision", revision.id());
        return new SourceRevisionReceipt(
                SourceRevisionReceipt.Outcome.CHANGED, mapSource(updated), mapRevision(revision), mapJob(job));
    }

    private SourceRevisionRow insertRevision(
            WorkspaceScope scope,
            SourceRow source,
            KnowledgeCapturedSnapshot snapshot,
            int revisionNumber,
            OffsetDateTime createdAt) {
        return repository.insertRevision(scope, new SourceRevisionRow(
                UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), source.id(), revisionNumber,
                snapshot.contentDigest(), snapshot.mediaType(), snapshot.bytes().length,
                snapshot.originalFilename(), EMPTY_JSON, snapshot.bytes(), SnapshotStatus.SNAPSHOTTED,
                null, null, createdAt));
    }

    private IngestionJobRow insertJob(
            WorkspaceScope scope,
            SourceRow source,
            SourceRevisionRow revision,
            JobKind jobKind,
            OffsetDateTime createdAt) {
        UUID jobId = UUID.randomUUID();
        return repository.insertJob(scope, new IngestionJobRow(
                jobId, scope.tenantId(), scope.workspaceId(), source.knowledgeBaseId(), source.id(), revision.id(),
                jobKind, JobStatus.QUEUED, JobStep.PARSING, SyncOutcome.CHANGED,
                0, MAXIMUM_ATTEMPTS, null, null, null, 1,
                jobKind.name().toLowerCase(Locale.ROOT) + ":" + jobId,
                false, null, null, EMPTY_JSON, false, null, null, createdAt, createdAt));
    }

    private IngestionJobRow insertSnapshotJob(
            WorkspaceScope scope,
            SourceRow source,
            JobKind jobKind,
            OffsetDateTime createdAt) {
        UUID jobId = UUID.randomUUID();
        return repository.insertJob(scope, new IngestionJobRow(
                jobId, scope.tenantId(), scope.workspaceId(), source.knowledgeBaseId(), source.id(), null,
                jobKind, JobStatus.QUEUED, JobStep.SNAPSHOTTING, null,
                0, MAXIMUM_ATTEMPTS, null, null, null, 1,
                jobKind.name().toLowerCase(Locale.ROOT) + ":" + jobId,
                false, null, null, EMPTY_JSON, false, null, null, createdAt, createdAt));
    }

    private WorkspaceScope scope(UUID workspaceId) {
        availability.requireEnabled();
        return workspaces.require(requiredId(workspaceId));
    }

    private BaseRow requireBase(WorkspaceScope scope, UUID knowledgeBaseId) {
        return repository.findBase(scope, requiredId(knowledgeBaseId))
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_BASE_NOT_FOUND", Category.NOT_FOUND));
    }

    private SourceRow requireSource(WorkspaceScope scope, UUID sourceId) {
        return repository.findSource(scope, requiredId(sourceId))
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_SOURCE_NOT_FOUND", Category.NOT_FOUND));
    }

    private SourceRow lockActiveSource(WorkspaceScope scope, UUID sourceId) {
        SourceRow source = repository.lockSource(scope, requiredId(sourceId))
                .orElseThrow(() -> problem("APVERO_KNOWLEDGE_SOURCE_NOT_FOUND", Category.NOT_FOUND));
        if (source.status() == SourceStatus.TOMBSTONED) {
            throw problem("APVERO_KNOWLEDGE_SOURCE_TOMBSTONED", Category.CONFLICT);
        }
        return source;
    }

    private void appendAudit(
            UUID workspaceId,
            KnowledgeCommandContext context,
            String action,
            String resourceType,
            UUID resourceId) {
        audit.append(workspaceId, actor(context), action, resourceType, resourceId.toString(), "SUCCEEDED",
                bounded(context == null ? null : context.sourceIp(), null, 64), trace(context));
    }

    private static String actor(KnowledgeCommandContext context) {
        return bounded(context == null ? null : context.actorId(), "system", 160);
    }

    private static String trace(KnowledgeCommandContext context) {
        return bounded(context == null ? null : context.traceId(), UUID.randomUUID().toString(), 80);
    }

    private static String bounded(String value, String fallback, int maximumLength) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized == null) {
            return null;
        }
        if (normalized.codePointCount(0, normalized.length()) <= maximumLength) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, maximumLength));
    }

    private static String requireSlug(String value) {
        if (value == null || !value.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$") || value.length() > 80) {
            throw problem("APVERO_KNOWLEDGE_BASE_SLUG_INVALID", Category.BAD_REQUEST);
        }
        return value;
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw problem("APVERO_KNOWLEDGE_NAME_INVALID", Category.BAD_REQUEST);
        }
        return value.trim();
    }

    private static String requireDescription(String value) {
        String description = value == null ? "" : value.trim();
        if (description.length() > 2000) {
            throw problem("APVERO_KNOWLEDGE_DESCRIPTION_INVALID", Category.BAD_REQUEST);
        }
        return description;
    }

    private static UUID requiredId(UUID value) {
        if (value == null) {
            throw problem("APVERO_KNOWLEDGE_IDENTIFIER_INVALID", Category.BAD_REQUEST);
        }
        return value;
    }

    private KnowledgeBase mapBase(BaseRow row) {
        return new KnowledgeBase(
                row.id(), row.tenantId(), row.workspaceId(), row.slug(), row.name(), row.description(),
                KnowledgeBase.Status.valueOf(row.status().name()), row.createdAt(), row.updatedAt());
    }

    private KnowledgeSource mapSource(SourceRow row) {
        return new KnowledgeSource(
                row.id(), row.tenantId(), row.workspaceId(), row.knowledgeBaseId(), row.name(),
                publicType(row.sourceType()), KnowledgeSource.Status.valueOf(row.status().name()),
                row.latestRevisionNumber(), row.latestRevisionId(), row.createdAt(), row.updatedAt());
    }

    private KnowledgeSourceRevision mapRevision(SourceRevisionRow row) {
        return new KnowledgeSourceRevision(
                row.id(), row.tenantId(), row.workspaceId(), row.sourceId(), row.revision(), row.contentDigest(),
                row.mediaType(), row.byteSize(), KnowledgeSourceRevision.SnapshotStatus.valueOf(row.snapshotStatus().name()),
                row.parserVersion(), row.chunkerVersion(), row.createdAt());
    }

    private KnowledgeIngestionJob mapJob(IngestionJobRow row) {
        return new KnowledgeIngestionJob(
                row.id(), row.tenantId(), row.workspaceId(), row.knowledgeBaseId(), row.sourceId(),
                row.sourceRevisionId(), KnowledgeIngestionJob.Status.valueOf(row.status().name()),
                KnowledgeIngestionJob.Step.valueOf(row.currentStep().name()), row.attemptCount(), row.retryable(),
                row.syncOutcome() == null ? null : KnowledgeIngestionJob.SyncOutcome.valueOf(row.syncOutcome().name()),
                row.nextAttemptAt(), row.errorCode(), row.createdAt(), row.startedAt(), row.completedAt(), row.updatedAt());
    }

    private static KnowledgeSource.Type publicType(SourceType type) {
        return KnowledgeSource.Type.valueOf(type.name());
    }

    private static String extensionFor(String mediaType) {
        if (mediaType == null) {
            return ".bin";
        }
        if (mediaType.startsWith("text/markdown")) {
            return ".md";
        }
        if (mediaType.startsWith("text/plain")) {
            return ".txt";
        }
        if (mediaType.equals("application/pdf")) {
            return ".pdf";
        }
        if (mediaType.contains("wordprocessingml")) {
            return ".docx";
        }
        return ".bin";
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static KnowledgeException problem(String code, Category category) {
        return new KnowledgeException(code, category);
    }
}
