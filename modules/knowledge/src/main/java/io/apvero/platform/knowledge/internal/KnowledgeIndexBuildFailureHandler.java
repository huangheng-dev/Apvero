package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.ErrorCategoryTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.OutcomeTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexBuildTelemetry.StaleOperationTag;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildRow;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.BuildStatus;
import java.util.Set;

final class KnowledgeIndexBuildFailureHandler {
    private static final Set<String> STALE_CODES = Set.of(
            "APVERO_KNOWLEDGE_INDEX_BUILD_LEASE_CONFLICT",
            "APVERO_KNOWLEDGE_INDEX_BUILD_STATE_CONFLICT",
            "APVERO_KNOWLEDGE_INDEX_BUILD_PUBLICATION_CONFLICT");
    private static final Set<String> TRANSIENT_CODES = Set.of(
            "APVERO_KNOWLEDGE_WEB_FETCH_FAILED",
            "APVERO_KNOWLEDGE_WEB_FETCH_TIMEOUT",
            "APVERO_KNOWLEDGE_WEB_DNS_FAILED");

    private final KnowledgeIndexBuildTransitionKernel kernel;
    private final KnowledgeIndexBuildTelemetry telemetry;

    KnowledgeIndexBuildFailureHandler(
            KnowledgeIndexBuildTransitionKernel kernel,
            KnowledgeIndexBuildTelemetry telemetry) {
        this.kernel = kernel;
        this.telemetry = telemetry;
    }

    HandlingResult handle(
            WorkspaceScope scope,
            BuildRow activeClaim,
            String leaseOwner,
            boolean providerDispatchMayBeAmbiguous,
            RuntimeException failure,
            StaleOperationTag operation) {
        if (isStale(failure)) {
            telemetry.staleLease(activeClaim.currentStep(), operation);
            return new HandlingResult(
                    activeClaim, OutcomeTag.STALE, ErrorCategoryTag.CONFLICT);
        }

        KnowledgeIndexBuildFailure normalized =
                normalize(providerDispatchMayBeAmbiguous, failure);
        try {
            BuildRow failed =
                    kernel.recordFailure(scope, activeClaim, leaseOwner, normalized);
            OutcomeTag outcome = failed.reconciliationRequired()
                    ? OutcomeTag.RECONCILIATION
                    : failed.status() == BuildStatus.RETRY_WAIT
                            ? OutcomeTag.RETRY
                            : OutcomeTag.FAILED;
            ErrorCategoryTag category = ErrorCategoryTag.from(normalized.category());
            if (outcome == OutcomeTag.RETRY) {
                telemetry.retry(activeClaim.currentStep(), category);
            }
            return new HandlingResult(failed, outcome, category);
        } catch (KnowledgeException conflict) {
            if (!isStale(conflict)) {
                throw conflict;
            }
            telemetry.staleLease(activeClaim.currentStep(), StaleOperationTag.FAILURE);
            return new HandlingResult(
                    activeClaim, OutcomeTag.STALE, ErrorCategoryTag.CONFLICT);
        }
    }

    private static KnowledgeIndexBuildFailure normalize(
            boolean providerDispatchMayBeAmbiguous,
            RuntimeException failure) {
        if (providerDispatchMayBeAmbiguous) {
            return new KnowledgeIndexBuildFailure(
                    "APVERO_KNOWLEDGE_INDEX_BUILD_OUTCOME_AMBIGUOUS",
                    KnowledgeIndexBuildFailure.Category.AMBIGUOUS,
                    false,
                    true);
        }
        if (failure instanceof KnowledgeException knowledgeFailure) {
            if ("APVERO_KNOWLEDGE_INDEX_BUILD_ROUTE_TIMEOUT_UNSAFE"
                    .equals(knowledgeFailure.code())) {
                return new KnowledgeIndexBuildFailure(
                        knowledgeFailure.code(),
                        KnowledgeIndexBuildFailure.Category.PERMANENT,
                        false,
                        false);
            }
            if (TRANSIENT_CODES.contains(knowledgeFailure.code())) {
                return new KnowledgeIndexBuildFailure(
                        knowledgeFailure.code(),
                        KnowledgeIndexBuildFailure.Category.TRANSIENT,
                        true,
                        false);
            }
            KnowledgeIndexBuildFailure.Category category =
                    knowledgeFailure.category() == KnowledgeException.Category.BAD_REQUEST
                                    || knowledgeFailure.category()
                                            == KnowledgeException.Category.UNPROCESSABLE
                            ? KnowledgeIndexBuildFailure.Category.VALIDATION
                            : KnowledgeIndexBuildFailure.Category.INTERNAL;
            return new KnowledgeIndexBuildFailure(
                    stableCode(knowledgeFailure),
                    category,
                    false,
                    false);
        }
        return new KnowledgeIndexBuildFailure(
                "APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_INTERNAL",
                KnowledgeIndexBuildFailure.Category.INTERNAL,
                false,
                false);
    }

    private static boolean isStale(RuntimeException failure) {
        return failure instanceof KnowledgeException knowledgeFailure
                && STALE_CODES.contains(knowledgeFailure.code());
    }

    private static String stableCode(KnowledgeException failure) {
        String code = failure.code();
        return code != null && code.matches("^APVERO_[A-Z0-9_]{1,112}$")
                ? code
                : "APVERO_KNOWLEDGE_INDEX_BUILD_RUNNER_INTERNAL";
    }

    record HandlingResult(
            BuildRow build,
            OutcomeTag outcome,
            ErrorCategoryTag errorCategory) {}
}
