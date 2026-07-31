package io.apvero.platform.runtime.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import io.apvero.platform.knowledge.KnowledgeRetrievalHit;
import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import io.apvero.platform.runtime.RecordRetrievalEvidenceCommand;
import io.apvero.platform.runtime.RunCitation;
import io.apvero.platform.runtime.RunEvidenceException;
import io.apvero.platform.runtime.RunRetrievalEvidence;
import io.apvero.platform.runtime.RunRetrievalExecution;
import io.apvero.platform.runtime.RunRetrievalHit;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
public class JooqRunRetrievalEvidenceRepository implements RunRetrievalEvidenceRepository {
    private static final Table<?> RUN = table("ai_run");
    private static final Table<?> RETRIEVAL = table("ai_run_retrieval");
    private static final Table<?> HIT = table("ai_run_retrieval_hit");

    private static final Field<UUID> ID = field("id", UUID.class);
    private static final Field<UUID> RUN_ID = field("run_id", UUID.class);
    private static final Field<UUID> TENANT_ID = field("tenant_id", UUID.class);
    private static final Field<UUID> WORKSPACE_ID = field("workspace_id", UUID.class);
    private static final Field<Integer> SEQUENCE = field("sequence", Integer.class);
    private static final Field<UUID> INDEX_VERSION_ID = field("index_version_id", UUID.class);
    private static final Field<String> INDEX_VERSION_REFERENCE = field("index_version_reference", String.class);
    private static final Field<UUID> POLICY_VERSION_ID = field("retrieval_policy_version_id", UUID.class);
    private static final Field<String> POLICY_VERSION_REFERENCE =
            field("retrieval_policy_version_reference", String.class);
    private static final Field<String> QUERY_DIGEST = field("query_digest", String.class);
    private static final Field<String> STATUS = field("status", String.class);
    private static final Field<Integer> HIT_COUNT = field("hit_count", Integer.class);
    private static final Field<Long> LATENCY_MS = field("latency_ms", Long.class);
    private static final Field<Long> RETENTION_VERSION = field("retention_decision_version", Long.class);
    private static final Field<OffsetDateTime> CREATED_AT = field("created_at", OffsetDateTime.class);

    private static final Field<UUID> RETRIEVAL_ID = field("retrieval_id", UUID.class);
    private static final Field<String> MARKER = field("marker", String.class);
    private static final Field<Integer> RANK = field("rank", Integer.class);
    private static final Field<BigDecimal> SCORE = field("score", BigDecimal.class);
    private static final Field<UUID> SOURCE_ID = field("source_id", UUID.class);
    private static final Field<UUID> SOURCE_REVISION_ID = field("source_revision_id", UUID.class);
    private static final Field<UUID> DOCUMENT_ID = field("document_id", UUID.class);
    private static final Field<UUID> CHUNK_ID = field("chunk_id", UUID.class);
    private static final Field<String> CONTENT_DIGEST = field("content_digest", String.class);
    private static final Field<String> RETAINED_CONTENT = field("retained_content", String.class);
    private static final Field<String> SOURCE_TITLE = field("source_title", String.class);
    private static final Field<String> SOURCE_TYPE = field("source_type", String.class);
    private static final Field<Integer> PAGE = field("page", Integer.class);
    private static final Field<String> HEADING = field("heading", String.class);
    private static final Field<Integer> PARAGRAPH = field("paragraph", Integer.class);
    private static final Field<Integer> LINE_START = field("line_start", Integer.class);
    private static final Field<Integer> LINE_END = field("line_end", Integer.class);
    private static final Field<Boolean> CITATION_VALIDATED = field("citation_validated", Boolean.class);
    private static final Field<UUID> HIT_RETRIEVAL_ID =
            field("ai_run_retrieval_hit.retrieval_id", UUID.class);
    private static final Field<UUID> HIT_RUN_ID =
            field("ai_run_retrieval_hit.run_id", UUID.class);
    private static final Field<UUID> HIT_WORKSPACE_ID =
            field("ai_run_retrieval_hit.workspace_id", UUID.class);
    private static final Field<Boolean> HIT_CITATION_VALIDATED =
            field("ai_run_retrieval_hit.citation_validated", Boolean.class);
    private static final Field<Integer> HIT_RANK =
            field("ai_run_retrieval_hit.rank", Integer.class);
    private static final Field<UUID> RETRIEVAL_PRIMARY_ID =
            field("ai_run_retrieval.id", UUID.class);
    private static final Field<String> RETRIEVAL_INDEX_REFERENCE =
            field("ai_run_retrieval.index_version_reference", String.class);
    private static final Field<Integer> RETRIEVAL_SEQUENCE =
            field("ai_run_retrieval.sequence", Integer.class);

    private final DSLContext sql;

    public JooqRunRetrievalEvidenceRepository(DSLContext sql) {
        this.sql = sql;
    }

    @Override
    public RunRetrievalExecution insert(
            UUID workspaceId,
            UUID runId,
            RecordRetrievalEvidenceCommand command) {
        UUID tenantId = lockRun(workspaceId, runId);
        int expectedSequence = sql.selectCount()
                .from(RETRIEVAL)
                .where(RUN_ID.eq(runId).and(WORKSPACE_ID.eq(workspaceId)))
                .fetchOne(0, int.class);
        if (command.sequence() != expectedSequence) {
            throw problem(
                    "APVERO_RUNTIME_RETRIEVAL_SEQUENCE_CONFLICT",
                    RunEvidenceException.Category.CONFLICT);
        }

        KnowledgeRetrievalResult result = command.result();
        requireOrderedHits(result.hits());
        UUID retrievalId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(RETRIEVAL)
                .columns(
                        ID, RUN_ID, TENANT_ID, WORKSPACE_ID, SEQUENCE,
                        INDEX_VERSION_ID, INDEX_VERSION_REFERENCE,
                        POLICY_VERSION_ID, POLICY_VERSION_REFERENCE,
                        QUERY_DIGEST, STATUS, HIT_COUNT, LATENCY_MS, RETENTION_VERSION, CREATED_AT)
                .values(
                        retrievalId, runId, tenantId, workspaceId, command.sequence(),
                        result.indexVersionId(), command.indexVersionReference(),
                        result.retrievalPolicyVersionId(), command.retrievalPolicyVersionReference(),
                        result.queryDigest(), result.status().name(), result.hits().size(), result.latencyMs(),
                        command.retentionDecisionVersion(), now)
                .execute();

        int markerOffset = sql.selectCount()
                .from(HIT)
                .where(RUN_ID.eq(runId).and(WORKSPACE_ID.eq(workspaceId)))
                .fetchOne(0, int.class);
        for (KnowledgeRetrievalHit hit : result.hits()) {
            insertHit(
                    retrievalId,
                    runId,
                    tenantId,
                    workspaceId,
                    "[K" + (markerOffset + hit.rank()) + "]",
                    hit,
                    command.discloseContent(),
                    now);
        }
        return findExecution(workspaceId, runId, retrievalId);
    }

    @Override
    public RunRetrievalEvidence find(UUID workspaceId, UUID runId) {
        requireRun(workspaceId, runId);
        List<RunRetrievalExecution> retrievals = sql.select(
                        ID, SEQUENCE, STATUS, INDEX_VERSION_ID, INDEX_VERSION_REFERENCE,
                        POLICY_VERSION_ID, POLICY_VERSION_REFERENCE, QUERY_DIGEST,
                        LATENCY_MS, RETENTION_VERSION, CREATED_AT)
                .from(RETRIEVAL)
                .where(RUN_ID.eq(runId).and(WORKSPACE_ID.eq(workspaceId)))
                .orderBy(SEQUENCE.asc())
                .fetch(record -> mapExecution(record, workspaceId, runId));
        return new RunRetrievalEvidence(runId, retrievals);
    }

    @Override
    public RunRetrievalEvidence lockForValidation(UUID workspaceId, UUID runId) {
        lockRun(workspaceId, runId);
        return find(workspaceId, runId);
    }

    @Override
    public void markCitationsValidated(
            UUID workspaceId, UUID runId, Set<String> markers) {
        if (markers == null || markers.isEmpty()) {
            throw problem(
                    "APVERO_CITATION_VALIDATION_FAILED",
                    RunEvidenceException.Category.BAD_REQUEST);
        }
        int available = sql.selectCount()
                .from(HIT)
                .where(RUN_ID.eq(runId)
                        .and(WORKSPACE_ID.eq(workspaceId))
                        .and(MARKER.in(markers)))
                .fetchOne(0, int.class);
        if (available != markers.size()) {
            throw problem(
                    "APVERO_CITATION_VALIDATION_FAILED",
                    RunEvidenceException.Category.BAD_REQUEST);
        }
        sql.update(HIT)
                .set(CITATION_VALIDATED, true)
                .where(RUN_ID.eq(runId)
                        .and(WORKSPACE_ID.eq(workspaceId))
                        .and(MARKER.in(markers))
                        .and(CITATION_VALIDATED.isFalse()))
                .execute();
    }

    @Override
    public List<RunCitation> findValidatedCitations(
            UUID workspaceId, UUID runId) {
        requireRun(workspaceId, runId);
        return sql.select(
                        MARKER,
                        RETRIEVAL_INDEX_REFERENCE,
                        SOURCE_ID,
                        SOURCE_REVISION_ID,
                        DOCUMENT_ID,
                        CHUNK_ID,
                        CONTENT_DIGEST,
                        RANK,
                        SCORE,
                        SOURCE_TITLE,
                        SOURCE_TYPE,
                        PAGE,
                        HEADING,
                        PARAGRAPH,
                        LINE_START,
                        LINE_END)
                .from(HIT)
                .join(RETRIEVAL)
                .on(HIT_RETRIEVAL_ID.eq(RETRIEVAL_PRIMARY_ID))
                .where(HIT_RUN_ID.eq(runId)
                        .and(HIT_WORKSPACE_ID.eq(workspaceId))
                        .and(HIT_CITATION_VALIDATED.isTrue()))
                .orderBy(RETRIEVAL_SEQUENCE.asc(), HIT_RANK.asc())
                .fetch(record -> new RunCitation(
                        record.get(MARKER),
                        record.get(RETRIEVAL_INDEX_REFERENCE),
                        record.get(SOURCE_ID),
                        record.get(SOURCE_REVISION_ID),
                        record.get(DOCUMENT_ID),
                        record.get(CHUNK_ID),
                        record.get(CONTENT_DIGEST),
                        record.get(RANK),
                        record.get(SCORE),
                        record.get(SOURCE_TITLE),
                        record.get(SOURCE_TYPE),
                        record.get(PAGE),
                        record.get(HEADING),
                        record.get(PARAGRAPH),
                        record.get(LINE_START),
                        record.get(LINE_END),
                        locator(
                                record.get(SOURCE_REVISION_ID),
                                record.get(PAGE),
                                record.get(PARAGRAPH),
                                record.get(LINE_START),
                                record.get(LINE_END))));
    }

    private UUID lockRun(UUID workspaceId, UUID runId) {
        return sql.select(TENANT_ID)
                .from(RUN)
                .where(ID.eq(runId).and(WORKSPACE_ID.eq(workspaceId)))
                .forUpdate()
                .fetchOptional(TENANT_ID)
                .orElseThrow(() -> problem(
                        "APVERO_RUNTIME_RUN_NOT_FOUND",
                        RunEvidenceException.Category.NOT_FOUND));
    }

    private void requireRun(UUID workspaceId, UUID runId) {
        if (!sql.fetchExists(
                sql.selectOne().from(RUN).where(ID.eq(runId).and(WORKSPACE_ID.eq(workspaceId))))) {
            throw problem(
                    "APVERO_RUNTIME_RUN_NOT_FOUND",
                    RunEvidenceException.Category.NOT_FOUND);
        }
    }

    private void insertHit(
            UUID retrievalId,
            UUID runId,
            UUID tenantId,
            UUID workspaceId,
            String marker,
            KnowledgeRetrievalHit hit,
            boolean discloseContent,
            OffsetDateTime now) {
        sql.insertInto(HIT)
                .columns(
                        ID, RETRIEVAL_ID, RUN_ID, TENANT_ID, WORKSPACE_ID, MARKER, RANK, SCORE,
                        SOURCE_ID, SOURCE_REVISION_ID, DOCUMENT_ID, CHUNK_ID, CONTENT_DIGEST,
                        RETAINED_CONTENT, SOURCE_TITLE, SOURCE_TYPE, PAGE, HEADING, PARAGRAPH,
                        LINE_START, LINE_END, CITATION_VALIDATED, CREATED_AT)
                .values(
                        UUID.randomUUID(), retrievalId, runId, tenantId, workspaceId, marker, hit.rank(), hit.score(),
                        hit.sourceId(), hit.sourceRevisionId(), hit.documentId(), hit.chunkId(), hit.contentDigest(),
                        discloseContent ? hit.content() : null, hit.sourceTitle(), hit.sourceType().name(),
                        hit.page(), hit.heading(), hit.paragraph(), hit.lineStart(), hit.lineEnd(), false, now)
                .execute();
    }

    private RunRetrievalExecution findExecution(UUID workspaceId, UUID runId, UUID retrievalId) {
        return sql.select(
                        ID, SEQUENCE, STATUS, INDEX_VERSION_ID, INDEX_VERSION_REFERENCE,
                        POLICY_VERSION_ID, POLICY_VERSION_REFERENCE, QUERY_DIGEST,
                        LATENCY_MS, RETENTION_VERSION, CREATED_AT)
                .from(RETRIEVAL)
                .where(ID.eq(retrievalId).and(RUN_ID.eq(runId)).and(WORKSPACE_ID.eq(workspaceId)))
                .fetchOptional(record -> mapExecution(record, workspaceId, runId))
                .orElseThrow();
    }

    private RunRetrievalExecution mapExecution(Record record, UUID workspaceId, UUID runId) {
        UUID retrievalId = record.get(ID);
        List<RunRetrievalHit> hits = sql.select(
                        MARKER, RANK, SCORE, SOURCE_ID, SOURCE_REVISION_ID, DOCUMENT_ID, CHUNK_ID,
                        CONTENT_DIGEST, RETAINED_CONTENT, SOURCE_TITLE, SOURCE_TYPE, PAGE, HEADING,
                        PARAGRAPH, LINE_START, LINE_END, CITATION_VALIDATED)
                .from(HIT)
                .where(RETRIEVAL_ID.eq(retrievalId)
                        .and(RUN_ID.eq(runId))
                        .and(WORKSPACE_ID.eq(workspaceId)))
                .orderBy(RANK.asc())
                .fetch(hit -> new RunRetrievalHit(
                        hit.get(MARKER), hit.get(RANK), hit.get(SCORE), hit.get(SOURCE_ID),
                        hit.get(SOURCE_REVISION_ID), hit.get(DOCUMENT_ID), hit.get(CHUNK_ID),
                        hit.get(CONTENT_DIGEST), hit.get(RETAINED_CONTENT), hit.get(SOURCE_TITLE),
                        hit.get(SOURCE_TYPE), hit.get(PAGE), hit.get(HEADING), hit.get(PARAGRAPH),
                        hit.get(LINE_START), hit.get(LINE_END), Boolean.TRUE.equals(hit.get(CITATION_VALIDATED))));
        return new RunRetrievalExecution(
                retrievalId,
                record.get(SEQUENCE),
                KnowledgeRetrievalResult.Status.valueOf(record.get(STATUS)),
                record.get(INDEX_VERSION_ID),
                record.get(INDEX_VERSION_REFERENCE),
                record.get(POLICY_VERSION_ID),
                record.get(POLICY_VERSION_REFERENCE),
                record.get(QUERY_DIGEST),
                hits,
                record.get(LATENCY_MS),
                record.get(RETENTION_VERSION),
                record.get(CREATED_AT));
    }

    private static void requireOrderedHits(List<KnowledgeRetrievalHit> hits) {
        for (int index = 0; index < hits.size(); index++) {
            if (hits.get(index).rank() != index + 1) {
                throw problem(
                        "APVERO_RUNTIME_RETRIEVAL_EVIDENCE_INVALID",
                        RunEvidenceException.Category.BAD_REQUEST);
            }
        }
    }

    private static RunEvidenceException problem(
            String code,
            RunEvidenceException.Category category) {
        return new RunEvidenceException(code, category);
    }

    private static String locator(
            UUID revisionId,
            Integer page,
            Integer paragraph,
            Integer lineStart,
            Integer lineEnd) {
        StringBuilder value = new StringBuilder(
                "/api/v1/knowledge-source-revisions/")
                .append(revisionId)
                .append("/content");
        String separator = "#";
        if (page != null) {
            value.append(separator).append("page=").append(page);
            separator = "&";
        }
        if (paragraph != null) {
            value.append(separator).append("paragraph=").append(paragraph);
            separator = "&";
        }
        if (lineStart != null) {
            value.append(separator).append("lines=").append(lineStart);
            if (lineEnd != null) {
                value.append('-').append(lineEnd);
            }
        }
        return value.toString();
    }
}
