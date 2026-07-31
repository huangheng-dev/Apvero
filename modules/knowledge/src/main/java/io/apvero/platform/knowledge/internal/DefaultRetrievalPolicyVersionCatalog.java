package io.apvero.platform.knowledge.internal;

import io.apvero.platform.capability.EmbeddingInputUnitEstimator;
import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.governance.RetentionPolicy;
import io.apvero.platform.governance.RetentionPolicyCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.CreateRetrievalPolicyVersionCommand;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.RetrievalPolicyOverlapHandling;
import io.apvero.platform.knowledge.RetrievalPolicyVersion;
import io.apvero.platform.knowledge.RetrievalPolicyVersionCatalog;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.RetrievalPolicyRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultRetrievalPolicyVersionCatalog implements RetrievalPolicyVersionCatalog {
    static final String RETRIEVAL_ALGORITHM_VERSION = "exact-cosine@1.0.0";
    static final String TOKEN_ESTIMATOR_VERSION = "apvero-utf8-byte@1.0.0";
    static final String TOKEN_ESTIMATOR_IMPLEMENTATION_VERSION = "apvero-utf8-byte-v1";
    private static final String NO_EVIDENCE = "NO_EVIDENCE";
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern SEMANTIC_VERSION =
            Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?$");

    private final KnowledgeAvailability availability;
    private final WorkspaceScopeCatalog workspaces;
    private final KnowledgeIndexPersistenceRepository policies;
    private final RetentionPolicyCatalog retentionPolicies;
    private final EmbeddingInputUnitEstimator tokenEstimator;
    private final AuditEventCatalog audit;

    public DefaultRetrievalPolicyVersionCatalog(
            KnowledgeAvailability availability,
            WorkspaceScopeCatalog workspaces,
            KnowledgeIndexPersistenceRepository policies,
            RetentionPolicyCatalog retentionPolicies,
            EmbeddingInputUnitEstimator tokenEstimator,
            AuditEventCatalog audit) {
        this.availability = availability;
        this.workspaces = workspaces;
        this.policies = policies;
        this.retentionPolicies = retentionPolicies;
        this.tokenEstimator = tokenEstimator;
        this.audit = audit;
    }

    @Override
    public List<RetrievalPolicyVersion> list(UUID workspaceId) {
        WorkspaceScope scope = scope(workspaceId);
        return policies.listPolicies(scope).stream()
                .map(DefaultRetrievalPolicyVersionCatalog::map)
                .toList();
    }

    @Override
    public RetrievalPolicyVersion get(UUID workspaceId, UUID policyVersionId) {
        WorkspaceScope scope = scope(workspaceId);
        if (policyVersionId == null) {
            throw problem(
                    "APVERO_KNOWLEDGE_IDENTIFIER_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        return policies.findPolicy(scope, policyVersionId)
                .map(DefaultRetrievalPolicyVersionCatalog::map)
                .orElseThrow(() -> problem(
                        "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_VERSION_NOT_FOUND",
                        KnowledgeException.Category.NOT_FOUND));
    }

    @Override
    public RetrievalPolicyVersion getByReference(UUID workspaceId, String reference) {
        WorkspaceScope scope = scope(workspaceId);
        if (reference == null || reference.isBlank() || reference.length() > 145) {
            throw problem(
                    "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_REFERENCE_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        return policies.findPolicyByReference(scope, reference.trim())
                .map(DefaultRetrievalPolicyVersionCatalog::map)
                .orElseThrow(() -> problem(
                        "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_VERSION_NOT_FOUND",
                        KnowledgeException.Category.NOT_FOUND));
    }

    @Override
    public boolean supportsExecution(RetrievalPolicyVersion policy) {
        return policy != null
                && RETRIEVAL_ALGORITHM_VERSION.equals(policy.retrievalAlgorithmVersion())
                && TOKEN_ESTIMATOR_VERSION.equals(policy.tokenEstimatorVersion())
                && policy.retentionPolicyVersionAtPublish() >= 1
                && NO_EVIDENCE.equals(policy.emptyEvidenceBehavior())
                && TOKEN_ESTIMATOR_IMPLEMENTATION_VERSION.equals(tokenEstimator.algorithmVersion());
    }

    @Override
    @Transactional
    public RetrievalPolicyVersion publish(
            UUID workspaceId,
            CreateRetrievalPolicyVersionCommand command,
            KnowledgeCommandContext context) {
        WorkspaceScope scope = scope(workspaceId);
        ValidatedPolicy requested = validate(command);
        requireSupportedEstimator();
        RetrievalPolicyRow sameVersion = policies.findPolicyBySlugAndVersion(
                        scope, requested.slug(), requested.version())
                .orElse(null);
        if (sameVersion != null) {
            return existingVersionOrConflict(sameVersion, requested);
        }

        RetentionPolicy retention = retentionPolicies.getOrCreate(scope.workspaceId());
        if (!scope.tenantId().equals(retention.tenantId())
                || !scope.workspaceId().equals(retention.workspaceId())
                || retention.version() < 1) {
            throw problem(
                    "APVERO_KNOWLEDGE_RETENTION_POLICY_INVALID",
                    KnowledgeException.Category.CONFLICT);
        }

        String digest = RetrievalPolicyDigests.canonical(
                RETRIEVAL_ALGORITHM_VERSION,
                TOKEN_ESTIMATOR_VERSION,
                retention.version(),
                requested.topK(),
                requested.maxContextTokens(),
                requested.minimumScore(),
                requested.overlapHandling().name(),
                NO_EVIDENCE);
        RetrievalPolicyRow sameDigest = policies.findPolicyByDigest(scope, digest).orElse(null);
        if (sameDigest != null) {
            if (sameDigest.slug().equals(requested.slug())
                    && sameDigest.version().equals(requested.version())) {
                return existingVersionOrConflict(sameDigest, requested);
            }
            throw duplicatePolicy();
        }

        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        RetrievalPolicyRow candidate = new RetrievalPolicyRow(
                UUID.randomUUID(),
                scope.tenantId(),
                scope.workspaceId(),
                requested.slug(),
                requested.version(),
                RETRIEVAL_ALGORITHM_VERSION,
                TOKEN_ESTIMATOR_VERSION,
                retention.version(),
                requested.topK(),
                requested.maxContextTokens(),
                requested.minimumScore(),
                requested.overlapHandling().name(),
                NO_EVIDENCE,
                digest,
                actor(context),
                createdAt);
        if (!policies.insertPolicyIfAbsent(scope, candidate)) {
            RetrievalPolicyRow concurrentVersion = policies.findPolicyBySlugAndVersion(
                            scope, requested.slug(), requested.version())
                    .orElse(null);
            if (concurrentVersion != null) {
                return existingVersionOrConflict(concurrentVersion, requested);
            }
            if (policies.findPolicyByDigest(scope, digest).isPresent()) {
                throw duplicatePolicy();
            }
            throw problem(
                    "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_CONCURRENT_CONFLICT",
                    KnowledgeException.Category.CONFLICT);
        }

        appendAudit(scope, context, candidate.id(), candidate.policyDigest());
        return map(candidate);
    }

    private WorkspaceScope scope(UUID workspaceId) {
        availability.requireEnabled();
        if (workspaceId == null) {
            throw problem(
                    "APVERO_KNOWLEDGE_IDENTIFIER_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        return workspaces.require(workspaceId);
    }

    private void requireSupportedEstimator() {
        if (!TOKEN_ESTIMATOR_IMPLEMENTATION_VERSION.equals(tokenEstimator.algorithmVersion())) {
            throw problem(
                    "APVERO_KNOWLEDGE_TOKEN_ESTIMATOR_UNSUPPORTED",
                    KnowledgeException.Category.CONFLICT);
        }
    }

    private RetrievalPolicyVersion existingVersionOrConflict(
            RetrievalPolicyRow existing, ValidatedPolicy requested) {
        boolean sameRequest = existing.slug().equals(requested.slug())
                && existing.version().equals(requested.version())
                && existing.topK() == requested.topK()
                && existing.maximumContextInputUnits() == requested.maxContextTokens()
                && existing.minimumScore().compareTo(requested.minimumScore()) == 0
                && existing.overlapBehavior().equals(requested.overlapHandling().name())
                && existing.retrievalAlgorithmVersion().equals(RETRIEVAL_ALGORITHM_VERSION)
                && existing.tokenEstimatorVersion().equals(TOKEN_ESTIMATOR_VERSION)
                && existing.noEvidenceBehavior().equals(NO_EVIDENCE);
        if (!sameRequest) {
            throw problem(
                    "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_VERSION_CONFLICT",
                    KnowledgeException.Category.CONFLICT);
        }
        String storedDigest = RetrievalPolicyDigests.canonical(
                existing.retrievalAlgorithmVersion(),
                existing.tokenEstimatorVersion(),
                existing.retentionPolicyVersionAtPublish(),
                existing.topK(),
                existing.maximumContextInputUnits(),
                existing.minimumScore(),
                existing.overlapBehavior(),
                existing.noEvidenceBehavior());
        if (!storedDigest.equals(existing.policyDigest())) {
            throw problem(
                    "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_INTEGRITY_INVALID",
                    KnowledgeException.Category.CONFLICT);
        }
        return map(existing);
    }

    private void appendAudit(
            WorkspaceScope scope,
            KnowledgeCommandContext context,
            UUID policyId,
            String policyDigest) {
        audit.appendWithDigest(
                scope.workspaceId(),
                actor(context),
                "knowledge.retrieval-policy.published",
                "retrieval-policy-version",
                policyId.toString(),
                "SUCCEEDED",
                boundedOrNull(context == null ? null : context.sourceIp(), 64),
                boundedOrDefault(context == null ? null : context.traceId(), 80,
                        UUID.randomUUID().toString()),
                policyDigest);
    }

    private static ValidatedPolicy validate(CreateRetrievalPolicyVersionCommand command) {
        if (command == null
                || command.slug() == null
                || command.slug().length() > 80
                || !SLUG.matcher(command.slug()).matches()
                || command.version() == null
                || command.version().length() > 64
                || !SEMANTIC_VERSION.matcher(command.version()).matches()
                || command.topK() == null
                || command.topK() < 1
                || command.topK() > 100
                || command.maxContextTokens() == null
                || command.maxContextTokens() < 128
                || command.maxContextTokens() > 200_000
                || command.minimumScore() == null
                || command.minimumScore().compareTo(BigDecimal.ZERO) < 0
                || command.minimumScore().compareTo(BigDecimal.ONE) > 0
                || command.overlapHandling() == null) {
            throw problem(
                    "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_REQUEST_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
        return new ValidatedPolicy(
                command.slug(),
                command.version(),
                command.topK(),
                command.maxContextTokens(),
                command.minimumScore().setScale(6, RoundingMode.HALF_UP).stripTrailingZeros(),
                command.overlapHandling());
    }

    private static RetrievalPolicyVersion map(RetrievalPolicyRow row) {
        return new RetrievalPolicyVersion(
                row.id(),
                row.tenantId(),
                row.workspaceId(),
                row.slug(),
                row.version(),
                row.slug() + "@" + row.version(),
                row.topK(),
                row.maximumContextInputUnits(),
                row.minimumScore(),
                RetrievalPolicyOverlapHandling.valueOf(row.overlapBehavior()),
                row.retrievalAlgorithmVersion(),
                row.tokenEstimatorVersion(),
                row.retentionPolicyVersionAtPublish(),
                row.policyDigest(),
                row.noEvidenceBehavior(),
                row.createdAt());
    }

    private static String actor(KnowledgeCommandContext context) {
        return boundedOrDefault(
                context == null ? null : context.actorId(), 160, "system");
    }

    private static String boundedOrDefault(String value, int maximum, String fallback) {
        String bounded = boundedOrNull(value, maximum);
        return bounded == null ? fallback : bounded;
    }

    private static String boundedOrNull(String value, int maximum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.codePointCount(0, normalized.length()) <= maximum
                ? normalized
                : normalized.substring(0, normalized.offsetByCodePoints(0, maximum));
    }

    private static KnowledgeException duplicatePolicy() {
        return problem(
                "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_DUPLICATE",
                KnowledgeException.Category.CONFLICT);
    }

    private static KnowledgeException problem(
            String code, KnowledgeException.Category category) {
        return new KnowledgeException(code, category);
    }

    private record ValidatedPolicy(
            String slug,
            String version,
            int topK,
            int maxContextTokens,
            BigDecimal minimumScore,
            RetrievalPolicyOverlapHandling overlapHandling) {}
}
