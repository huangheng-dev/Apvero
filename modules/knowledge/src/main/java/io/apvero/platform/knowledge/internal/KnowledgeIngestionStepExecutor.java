package io.apvero.platform.knowledge.internal;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeJobLeaseService.KnowledgeJobFailure;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.ErrorCategory;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.IngestionJobRow;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.JobStep;
import io.apvero.platform.knowledge.internal.KnowledgePersistenceRecords.SourceRevisionRow;
import io.apvero.platform.knowledge.internal.KnowledgeWorkerModels.ProcessedBatch;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeIngestionStepExecutor {
    private final KnowledgePersistenceRepository repository;
    private final KnowledgeWebCaptureProcessor webCapture;
    private final KnowledgeWorkerClient worker;
    private final KnowledgeJobLeaseService leases;
    private final KnowledgeProcessingCompletion completion;
    private final MeterRegistry meters;

    KnowledgeIngestionStepExecutor(
            KnowledgePersistenceRepository repository,
            KnowledgeWebCaptureProcessor webCapture,
            KnowledgeWorkerClient worker,
            KnowledgeJobLeaseService leases,
            KnowledgeProcessingCompletion completion,
            MeterRegistry meters) {
        this.repository = repository;
        this.webCapture = webCapture;
        this.worker = worker;
        this.leases = leases;
        this.completion = completion;
        this.meters = meters;
    }

    void execute(WorkspaceScope scope, IngestionJobRow claimed, String leaseOwner) {
        if (claimed.currentStep() == JobStep.SNAPSHOTTING) {
            var result = webCapture.process(scope, claimed, leaseOwner);
            meters.summary("apvero.knowledge.ingestion.input.bytes", "source_type", "web")
                    .record(result.revision().byteSize());
            return;
        }
        if (claimed.currentStep() != JobStep.PARSING && claimed.currentStep() != JobStep.CHUNKING) {
            throw new KnowledgeException(
                    "APVERO_KNOWLEDGE_JOB_STATE_CONFLICT", KnowledgeException.Category.CONFLICT);
        }
        SourceRevisionRow revision = repository.findRevision(scope, claimed.sourceRevisionId())
                .orElseThrow(() -> new KnowledgeException(
                        "APVERO_KNOWLEDGE_REVISION_NOT_FOUND", KnowledgeException.Category.NOT_FOUND));
        String sourceType = repository.findSource(scope, claimed.sourceId())
                .map(source -> source.sourceType().name().toLowerCase(java.util.Locale.ROOT))
                .orElse("unknown");
        meters.summary("apvero.knowledge.ingestion.input.bytes", "source_type", sourceType)
                .record(revision.byteSize());
        Timer.Sample workerSample = Timer.start(meters);
        ProcessedBatch batch;
        try {
            batch = worker.process(requestId(claimed), revision);
            workerSample.stop(Timer.builder("apvero.knowledge.worker.latency")
                    .tag("outcome", "succeeded").tag("source_type", sourceType).register(meters));
        } catch (RuntimeException failure) {
            workerSample.stop(Timer.builder("apvero.knowledge.worker.latency")
                    .tag("outcome", "failed").tag("source_type", sourceType).register(meters));
            throw failure;
        }
        int chunkCount = batch.documents().stream().mapToInt(document -> document.chunks().size()).sum();
        meters.summary("apvero.knowledge.ingestion.output.documents",
                        "parser_version", batch.parserVersion()).record(batch.documents().size());
        meters.summary("apvero.knowledge.ingestion.output.chunks",
                        "chunker_version", batch.chunkerVersion()).record(chunkCount);
        IngestionJobRow chunking = claimed.currentStep() == JobStep.PARSING
                ? leases.advanceToChunking(scope, claimed, leaseOwner) : claimed;
        completion.completeLeased(scope, chunking.id(), chunking.lockVersion(), leaseOwner, batch);
    }

    KnowledgeJobFailure classify(Throwable failure) {
        if (failure instanceof KnowledgeWorkerException workerFailure) {
            return new KnowledgeJobFailure(workerFailure.code(),
                    workerFailure.retryable() ? ErrorCategory.TRANSIENT : ErrorCategory.PERMANENT,
                    workerFailure.retryable());
        }
        if (failure instanceof KnowledgeException knowledgeFailure) {
            if (securityCode(knowledgeFailure.code())) {
                return new KnowledgeJobFailure(knowledgeFailure.code(), ErrorCategory.SECURITY, false);
            }
            boolean transientFailure = knowledgeFailure.code().equals("APVERO_KNOWLEDGE_WEB_FETCH_FAILED")
                    || knowledgeFailure.code().equals("APVERO_KNOWLEDGE_WEB_FETCH_TIMEOUT")
                    || knowledgeFailure.code().equals("APVERO_KNOWLEDGE_WEB_DNS_FAILED");
            ErrorCategory category = transientFailure ? ErrorCategory.TRANSIENT
                    : knowledgeFailure.category() == KnowledgeException.Category.BAD_REQUEST
                            ? ErrorCategory.VALIDATION : ErrorCategory.PERMANENT;
            return new KnowledgeJobFailure(knowledgeFailure.code(), category, transientFailure);
        }
        return new KnowledgeJobFailure(
                "APVERO_KNOWLEDGE_INGESTION_INTERNAL", ErrorCategory.INTERNAL, true);
    }

    private static boolean securityCode(String code) {
        return code.equals("APVERO_KNOWLEDGE_WEB_DESTINATION_DENIED")
                || code.equals("APVERO_KNOWLEDGE_WEB_REDIRECT_DOWNGRADE")
                || code.equals("APVERO_KNOWLEDGE_WEB_REDIRECT_INVALID");
    }

    private static UUID requestId(IngestionJobRow job) {
        return UUID.nameUUIDFromBytes(("apvero:knowledge-worker:" + job.id() + ':' + job.attemptCount())
                .getBytes(StandardCharsets.UTF_8));
    }
}
