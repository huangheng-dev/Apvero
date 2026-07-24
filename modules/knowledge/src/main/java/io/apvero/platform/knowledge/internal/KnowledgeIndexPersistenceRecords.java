package io.apvero.platform.knowledge.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

final class KnowledgeIndexPersistenceRecords {
    private KnowledgeIndexPersistenceRecords() {}

    enum IndexStatus {
        ACTIVE,
        ARCHIVED
    }

    enum BuildStatus {
        QUEUED,
        EMBEDDING,
        INDEXING,
        VALIDATING,
        READY,
        RETRY_WAIT,
        FAILED,
        CANCELLED
    }

    enum BuildStep {
        EMBEDDING,
        INDEXING,
        VALIDATING,
        COMPLETE
    }

    record RetrievalPolicyRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            String slug,
            String version,
            String retrievalAlgorithmVersion,
            String tokenEstimatorVersion,
            long retentionPolicyVersionAtPublish,
            int topK,
            int maximumContextInputUnits,
            BigDecimal minimumScore,
            String overlapBehavior,
            String noEvidenceBehavior,
            String policyDigest,
            String createdBy,
            OffsetDateTime createdAt) {}

    record IndexRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID knowledgeBaseId,
            String slug,
            String name,
            IndexStatus status,
            long metadataVersion,
            int versionCount,
            UUID latestReadyVersionId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    record BuildRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID knowledgeIndexId,
            UUID knowledgeBaseId,
            String requestedVersion,
            UUID embeddingRouteId,
            String embeddingRouteReference,
            int vectorDimension,
            int maximumInputTokens,
            int maximumBatchSize,
            String normalization,
            String requestDigest,
            String sourceSetDigest,
            int requestedSourceCount,
            int requestedChunkCount,
            BuildStatus status,
            BuildStep currentStep,
            int attemptCount,
            int maximumAttempts,
            boolean retryable,
            OffsetDateTime nextAttemptAt,
            String leaseOwner,
            OffsetDateTime leaseUntil,
            long lockVersion,
            boolean cancellationRequested,
            int embeddedEntryCount,
            int validatedEntryCount,
            Integer lastDurableChunkOrdinal,
            String validationDigest,
            String artifactDigest,
            UUID publishedVersionId,
            String errorCode,
            String errorCategory,
            boolean reconciliationRequired,
            String failureMetadataJson,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    record BuildRevisionRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID knowledgeIndexBuildId,
            UUID knowledgeIndexId,
            UUID knowledgeBaseId,
            UUID sourceId,
            UUID sourceRevisionId,
            String sourceContentDigest,
            String parserVersion,
            String chunkerVersion,
            int sourceSetOrdinal,
            OffsetDateTime createdAt) {}

    record EntryRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID knowledgeIndexBuildId,
            UUID knowledgeIndexId,
            UUID knowledgeBaseId,
            UUID sourceId,
            UUID sourceRevisionId,
            UUID documentId,
            UUID chunkId,
            int entryOrdinal,
            List<Float> embedding,
            int vectorDimension,
            String vectorDigest,
            String normalizedInputDigest,
            int batchOrdinal,
            UUID embeddingRouteId,
            String embeddingRouteReference,
            OffsetDateTime createdAt) {
        EntryRow {
            embedding = List.copyOf(embedding);
        }
    }

    record VersionRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID knowledgeIndexId,
            UUID knowledgeIndexBuildId,
            String version,
            String reference,
            UUID embeddingRouteId,
            String embeddingRouteReference,
            int vectorDimension,
            int sourceCount,
            int chunkCount,
            String artifactDigest,
            String status,
            OffsetDateTime publishedAt) {}
}
