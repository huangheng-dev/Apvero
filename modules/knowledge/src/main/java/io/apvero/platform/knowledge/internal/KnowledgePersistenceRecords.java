package io.apvero.platform.knowledge.internal;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

final class KnowledgePersistenceRecords {
    private KnowledgePersistenceRecords() {}

    enum BaseStatus {
        ACTIVE,
        ARCHIVED
    }

    enum SourceType {
        TEXT,
        MARKDOWN,
        PDF,
        DOCX,
        WEB
    }

    enum SourceStatus {
        ACTIVE,
        TOMBSTONED
    }

    enum SnapshotStatus {
        SNAPSHOTTED,
        REJECTED
    }

    enum JobKind {
        CREATE_SOURCE,
        ADD_REVISION,
        SYNCHRONIZE_SOURCE
    }

    enum JobStatus {
        QUEUED,
        SNAPSHOTTING,
        PARSING,
        CHUNKING,
        READY,
        RETRY_WAIT,
        FAILED,
        CANCELLED
    }

    enum JobStep {
        SNAPSHOTTING,
        PARSING,
        CHUNKING,
        COMPLETE
    }

    enum SyncOutcome {
        CHANGED,
        UNCHANGED
    }

    enum ErrorCategory {
        VALIDATION,
        SECURITY,
        TRANSIENT,
        PERMANENT,
        INTERNAL
    }

    record BaseRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            String slug,
            String name,
            String description,
            BaseStatus status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    record SourceRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID knowledgeBaseId,
            String name,
            SourceType sourceType,
            SourceStatus status,
            String canonicalWebUri,
            int latestRevisionNumber,
            UUID latestRevisionId,
            long version,
            OffsetDateTime tombstonedAt,
            String tombstonedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    record SourceRevisionRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID sourceId,
            int revision,
            String contentDigest,
            String mediaType,
            long byteSize,
            String originalFilename,
            String captureMetadataJson,
            byte[] snapshotBytes,
            SnapshotStatus snapshotStatus,
            String parserVersion,
            String chunkerVersion,
            OffsetDateTime createdAt) {

        SourceRevisionRow {
            snapshotBytes = snapshotBytes == null ? null : Arrays.copyOf(snapshotBytes, snapshotBytes.length);
        }

        @Override
        public byte[] snapshotBytes() {
            return snapshotBytes == null ? null : Arrays.copyOf(snapshotBytes, snapshotBytes.length);
        }
    }

    record DocumentRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID sourceRevisionId,
            int ordinal,
            String title,
            String normalizedTextDigest,
            String parserVersion,
            String processingProfile,
            OffsetDateTime createdAt) {}

    record ChunkRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID sourceRevisionId,
            UUID documentId,
            int ordinal,
            String text,
            String contentDigest,
            int startOffset,
            int endOffset,
            Integer pageNumber,
            String heading,
            Integer paragraphNumber,
            Integer lineStart,
            Integer lineEnd,
            String chunkerVersion,
            OffsetDateTime createdAt) {}

    record IngestionJobRow(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID knowledgeBaseId,
            UUID sourceId,
            UUID sourceRevisionId,
            JobKind jobKind,
            JobStatus status,
            JobStep currentStep,
            SyncOutcome syncOutcome,
            int attemptCount,
            int maximumAttempts,
            OffsetDateTime nextAttemptAt,
            String leaseOwner,
            OffsetDateTime leaseUntil,
            long lockVersion,
            String idempotencyKey,
            boolean retryable,
            String errorCode,
            ErrorCategory errorCategory,
            String failureMetadataJson,
            boolean cancellationRequested,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}
}
