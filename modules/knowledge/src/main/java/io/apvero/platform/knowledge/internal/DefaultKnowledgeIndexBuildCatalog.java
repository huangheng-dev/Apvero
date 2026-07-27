package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingRouteCatalog;
import io.apvero.platform.capability.EmbeddingRouteSnapshot;
import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.CreateKnowledgeIndexBuildCommand;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeIndexBuild;
import io.apvero.platform.knowledge.KnowledgeIndexBuildCatalog;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildSourceCandidateRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStep;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.IndexStatus;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.BaseStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultKnowledgeIndexBuildCatalog implements KnowledgeIndexBuildCatalog {
    private static final Pattern SEMANTIC_VERSION =
            Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?$");
    private static final int MAXIMUM_SOURCE_REVISIONS = 10_000;
    private static final int MAXIMUM_ATTEMPTS = 3;

    private final KnowledgeAvailability availability;
    private final WorkspaceScopeCatalog workspaces;
    private final KnowledgePersistenceRepository knowledge;
    private final KnowledgeIndexPersistenceRepository indexes;
    private final EmbeddingRouteCatalog routes;
    private final AuditEventCatalog audit;

    public DefaultKnowledgeIndexBuildCatalog(
            KnowledgeAvailability availability,
            WorkspaceScopeCatalog workspaces,
            KnowledgePersistenceRepository knowledge,
            KnowledgeIndexPersistenceRepository indexes,
            EmbeddingRouteCatalog routes,
            AuditEventCatalog audit) {
        this.availability = availability;
        this.workspaces = workspaces;
        this.knowledge = knowledge;
        this.indexes = indexes;
        this.routes = routes;
        this.audit = audit;
    }

    @Override
    public List<KnowledgeIndexBuild> list(UUID workspaceId, UUID indexId) {
        WorkspaceScope scope = scope(workspaceId);
        UUID requiredIndexId = required(indexId);
        requireIndex(scope, requiredIndexId);
        return indexes.listBuilds(scope, requiredIndexId).stream()
                .map(DefaultKnowledgeIndexBuildCatalog::map)
                .toList();
    }

    @Override
    public KnowledgeIndexBuild get(UUID workspaceId, UUID buildId) {
        WorkspaceScope scope = scope(workspaceId);
        return map(requireBuild(scope, required(buildId)));
    }

    @Override
    @Transactional
    public KnowledgeIndexBuild create(
            UUID workspaceId,
            UUID indexId,
            CreateKnowledgeIndexBuildCommand command,
            KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        UUID requiredIndexId = required(indexId);
        ValidatedRequest request = validate(command);
        IndexRow index = indexes.lockIndex(scope, requiredIndexId)
                .orElseThrow(DefaultKnowledgeIndexBuildCatalog::indexNotFound);
        if (index.status() != IndexStatus.ACTIVE) {
            throw problem("APVERO_KNOWLEDGE_INDEX_NOT_ACTIVE", KnowledgeException.Category.CONFLICT);
        }

        BuildRow existing = indexes.findBuildByIndexAndVersion(scope, index.id(), request.version())
                .orElse(null);
        if (existing != null) {
            return existingOrConflict(scope, existing, request);
        }

        if (knowledge.findBase(scope, index.knowledgeBaseId())
                .filter(base -> base.status() == BaseStatus.ACTIVE)
                .isEmpty()) {
            throw problem("APVERO_KNOWLEDGE_BUILD_SOURCE_INELIGIBLE", KnowledgeException.Category.CONFLICT);
        }

        EmbeddingRouteSnapshot route = routes.findEmbeddingRoute(scope.workspaceId(), request.embeddingRouteId())
                .orElseThrow(() -> problem(
                        "APVERO_KNOWLEDGE_EMBEDDING_ROUTE_NOT_FOUND", KnowledgeException.Category.NOT_FOUND));
        if (!route.availableForNewBuilds()) {
            throw problem("APVERO_KNOWLEDGE_EMBEDDING_ROUTE_NOT_READY", KnowledgeException.Category.CONFLICT);
        }

        List<BuildSourceCandidateRow> sources = indexes.listBuildSourceCandidates(
                scope, index.knowledgeBaseId(), request.sourceRevisionIds());
        if (sources.size() != request.sourceRevisionIds().size()
                || sources.stream().map(BuildSourceCandidateRow::sourceId).distinct().count() != sources.size()) {
            throw problem("APVERO_KNOWLEDGE_BUILD_SOURCE_INELIGIBLE", KnowledgeException.Category.CONFLICT);
        }
        List<BuildSourceCandidateRow> ordered = sources.stream()
                .sorted(java.util.Comparator.comparing(BuildSourceCandidateRow::sourceId)
                        .thenComparing(BuildSourceCandidateRow::sourceRevisionId))
                .toList();
        int chunkCount;
        try {
            chunkCount = ordered.stream()
                    .mapToInt(BuildSourceCandidateRow::chunkCount)
                    .reduce(0, Math::addExact);
        } catch (ArithmeticException exception) {
            throw problem("APVERO_KNOWLEDGE_BUILD_SIZE_INVALID", KnowledgeException.Category.BAD_REQUEST);
        }
        if (chunkCount < 1) {
            throw problem("APVERO_KNOWLEDGE_BUILD_SOURCE_INELIGIBLE", KnowledgeException.Category.CONFLICT);
        }

        String sourceSetDigest = KnowledgeIndexBuildDigests.sourceSet(ordered);
        String requestDigest = KnowledgeIndexBuildDigests.request(
                scope, index, request.version(), route, ordered);
        OffsetDateTime now = now();
        BuildRow created = indexes.insertBuild(scope, new BuildRow(
                UUID.randomUUID(),
                scope.tenantId(),
                scope.workspaceId(),
                index.id(),
                index.knowledgeBaseId(),
                request.version(),
                route.id(),
                route.reference(),
                route.profile().dimension(),
                route.profile().maximumInputTokens(),
                route.profile().maximumBatchSize(),
                route.profile().normalization().name(),
                requestDigest,
                sourceSetDigest,
                ordered.size(),
                chunkCount,
                BuildStatus.QUEUED,
                BuildStep.EMBEDDING,
                0,
                MAXIMUM_ATTEMPTS,
                false,
                null,
                null,
                null,
                1,
                false,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "{}",
                null,
                null,
                now,
                now));
        for (int ordinal = 0; ordinal < ordered.size(); ordinal++) {
            BuildSourceCandidateRow source = ordered.get(ordinal);
            indexes.insertBuildRevision(scope, new BuildRevisionRow(
                    UUID.randomUUID(),
                    scope.tenantId(),
                    scope.workspaceId(),
                    created.id(),
                    index.id(),
                    index.knowledgeBaseId(),
                    source.sourceId(),
                    source.sourceRevisionId(),
                    source.sourceContentDigest(),
                    source.parserVersion(),
                    source.chunkerVersion(),
                    ordinal,
                    now));
        }
        appendAudit(scope, context, "knowledge.index-build.requested", created.id());
        return map(created);
    }

    @Override
    @Transactional
    public KnowledgeIndexBuild retry(
            UUID workspaceId,
            UUID buildId,
            KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        BuildRow build = indexes.lockBuild(scope, required(buildId))
                .orElseThrow(DefaultKnowledgeIndexBuildCatalog::buildNotFound);
        if (build.status() != BuildStatus.FAILED || !build.retryable()) {
            throw problem("APVERO_KNOWLEDGE_BUILD_NOT_RETRYABLE", KnowledgeException.Category.CONFLICT);
        }
        BuildRow retried = indexes.retryFailedBuild(scope, build.id(), build.lockVersion(), now())
                .orElseThrow(() -> problem(
                        "APVERO_KNOWLEDGE_BUILD_CONCURRENT_MODIFICATION",
                        KnowledgeException.Category.CONFLICT));
        appendAudit(scope, context, "knowledge.index-build.retry-requested", retried.id());
        return map(retried);
    }

    @Override
    @Transactional
    public KnowledgeIndexBuild cancel(
            UUID workspaceId,
            UUID buildId,
            KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        BuildRow build = indexes.lockBuild(scope, required(buildId))
                .orElseThrow(DefaultKnowledgeIndexBuildCatalog::buildNotFound);
        if ((build.status() != BuildStatus.QUEUED && build.status() != BuildStatus.RETRY_WAIT)
                || build.leaseOwner() != null
                || build.leaseUntil() != null) {
            throw problem("APVERO_KNOWLEDGE_BUILD_NOT_CANCELLABLE", KnowledgeException.Category.CONFLICT);
        }
        BuildRow cancelled = indexes.cancelWaitingBuild(scope, build.id(), build.lockVersion(), now())
                .orElseThrow(() -> problem(
                        "APVERO_KNOWLEDGE_BUILD_CONCURRENT_MODIFICATION",
                        KnowledgeException.Category.CONFLICT));
        appendAudit(scope, context, "knowledge.index-build.cancelled", cancelled.id());
        return map(cancelled);
    }

    private KnowledgeIndexBuild existingOrConflict(
            WorkspaceScope scope, BuildRow existing, ValidatedRequest request) {
        Set<UUID> existingRevisions = new HashSet<>();
        for (BuildRevisionRow revision : indexes.listBuildRevisions(scope, existing.id())) {
            existingRevisions.add(revision.sourceRevisionId());
        }
        if (!existing.embeddingRouteId().equals(request.embeddingRouteId())
                || existingRevisions.size() != request.sourceRevisionIds().size()
                || !existingRevisions.equals(Set.copyOf(request.sourceRevisionIds()))) {
            throw problem(
                    "APVERO_KNOWLEDGE_BUILD_VERSION_CONFLICT",
                    KnowledgeException.Category.CONFLICT);
        }
        return map(existing);
    }

    private IndexRow requireIndex(WorkspaceScope scope, UUID indexId) {
        return indexes.findIndex(scope, indexId)
                .orElseThrow(DefaultKnowledgeIndexBuildCatalog::indexNotFound);
    }

    private BuildRow requireBuild(WorkspaceScope scope, UUID buildId) {
        return indexes.findBuild(scope, buildId)
                .orElseThrow(DefaultKnowledgeIndexBuildCatalog::buildNotFound);
    }

    private WorkspaceScope scope(UUID workspaceId) {
        availability.requireEnabled();
        return workspaces.require(required(workspaceId));
    }

    private void appendAudit(
            WorkspaceScope scope,
            KnowledgeCommandContext context,
            String action,
            UUID buildId) {
        String actor = context == null || context.actorId() == null || context.actorId().isBlank()
                ? "system"
                : bounded(context.actorId(), 160);
        String trace = context == null || context.traceId() == null || context.traceId().isBlank()
                ? UUID.randomUUID().toString()
                : bounded(context.traceId(), 80);
        String sourceIp = context == null || context.sourceIp() == null || context.sourceIp().isBlank()
                ? null
                : bounded(context.sourceIp(), 64);
        audit.append(
                scope.workspaceId(),
                actor,
                action,
                "knowledge-index-build",
                buildId.toString(),
                "SUCCEEDED",
                sourceIp,
                trace);
    }

    private static ValidatedRequest validate(CreateKnowledgeIndexBuildCommand command) {
        if (command == null
                || command.version() == null
                || command.version().length() > 64
                || !SEMANTIC_VERSION.matcher(command.version()).matches()
                || command.embeddingRouteId() == null
                || command.sourceRevisionIds() == null
                || command.sourceRevisionIds().isEmpty()
                || command.sourceRevisionIds().size() > MAXIMUM_SOURCE_REVISIONS
                || command.sourceRevisionIds().stream().anyMatch(java.util.Objects::isNull)
                || command.sourceRevisionIds().stream().distinct().count()
                        != command.sourceRevisionIds().size()) {
            throw problem("APVERO_KNOWLEDGE_BUILD_REQUEST_INVALID", KnowledgeException.Category.BAD_REQUEST);
        }
        return new ValidatedRequest(
                command.version(),
                command.embeddingRouteId(),
                new ArrayList<>(command.sourceRevisionIds()));
    }

    private static KnowledgeIndexBuild map(BuildRow row) {
        return new KnowledgeIndexBuild(
                row.id(),
                row.tenantId(),
                row.workspaceId(),
                row.knowledgeIndexId(),
                row.requestedVersion(),
                row.embeddingRouteId(),
                row.embeddingRouteReference(),
                KnowledgeIndexBuild.Status.valueOf(row.status().name()),
                row.requestedSourceCount(),
                row.requestedChunkCount(),
                row.vectorDimension(),
                row.attemptCount(),
                row.retryable(),
                row.validationDigest(),
                row.publishedVersionId(),
                row.errorCode(),
                row.createdAt(),
                row.completedAt(),
                row.updatedAt());
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
                ? normalized
                : normalized.substring(0, normalized.offsetByCodePoints(0, maximum));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static KnowledgeException indexNotFound() {
        return problem("APVERO_KNOWLEDGE_INDEX_NOT_FOUND", KnowledgeException.Category.NOT_FOUND);
    }

    private static KnowledgeException buildNotFound() {
        return problem("APVERO_KNOWLEDGE_BUILD_NOT_FOUND", KnowledgeException.Category.NOT_FOUND);
    }

    private static KnowledgeException problem(String code, KnowledgeException.Category category) {
        return new KnowledgeException(code, category);
    }

    private record ValidatedRequest(
            String version,
            UUID embeddingRouteId,
            List<UUID> sourceRevisionIds) {
        private ValidatedRequest {
            sourceRevisionIds = List.copyOf(sourceRevisionIds);
        }
    }
}
