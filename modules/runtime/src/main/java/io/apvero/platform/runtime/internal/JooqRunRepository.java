package io.apvero.platform.runtime.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.apvero.platform.application.AiApplication;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.runtime.ProviderResult;
import io.apvero.platform.runtime.RunRecord;
import io.apvero.platform.runtime.RunStatus;
import io.apvero.platform.runtime.UsageSummary;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
public class JooqRunRepository implements RunRepository {
    private static final Table<?> RUN = table("ai_run");
    private static final Field<UUID> ID = field("id", UUID.class);
    private static final Field<UUID> TENANT_ID = field("tenant_id", UUID.class);
    private static final Field<UUID> WORKSPACE_ID = field("workspace_id", UUID.class);
    private static final Field<UUID> APPLICATION_ID = field("application_id", UUID.class);
    private static final Field<UUID> RELEASE_ID = field("release_bundle_id", UUID.class);
    private static final Field<String> STATUS = field("status", String.class);
    private static final Field<String> PROVIDER_ID = field("provider_id", String.class);
    private static final Field<JSONB> INPUT = field("input", JSONB.class);
    private static final Field<JSONB> OUTPUT = field("output", JSONB.class);
    private static final Field<Long> LATENCY_MS = field("latency_ms", Long.class);
    private static final Field<Integer> PROMPT_TOKENS = field("prompt_tokens", Integer.class);
    private static final Field<Integer> COMPLETION_TOKENS = field("completion_tokens", Integer.class);
    private static final Field<Long> COST_MICROS = field("cost_micros", Long.class);
    private static final Field<String> TRACE_ID = field("trace_id", String.class);
    private static final Field<String> FAILURE_CATEGORY = field("failure_category", String.class);
    private static final Field<String> FAILURE_MESSAGE = field("failure_message", String.class);
    private static final Field<OffsetDateTime> CREATED_AT = field("created_at", OffsetDateTime.class);

    private final DSLContext sql;
    private final ObjectMapper json;

    public JooqRunRepository(DSLContext sql, ObjectMapper json) {
        this.sql = sql;
        this.json = json;
    }

    @Override
    public List<RunRecord> findAll(UUID workspaceId) {
        return sql.select(ID, TENANT_ID, WORKSPACE_ID, APPLICATION_ID, RELEASE_ID, STATUS, PROVIDER_ID,
                        INPUT, OUTPUT, LATENCY_MS, PROMPT_TOKENS, COMPLETION_TOKENS, COST_MICROS, TRACE_ID,
                        FAILURE_CATEGORY, FAILURE_MESSAGE, CREATED_AT)
                .from(RUN)
                .where(WORKSPACE_ID.eq(workspaceId))
                .orderBy(CREATED_AT.desc())
                .limit(200)
                .fetch(this::map);
    }

    @Override
    public RunRecord insert(
            AiApplication application,
            ReleaseBundle release,
            String providerId,
            JsonNode input,
            ProviderResult result,
            long latencyMs,
            String traceId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(RUN)
                .columns(ID, TENANT_ID, WORKSPACE_ID, APPLICATION_ID, RELEASE_ID, STATUS, PROVIDER_ID,
                        INPUT, OUTPUT, LATENCY_MS, PROMPT_TOKENS, COMPLETION_TOKENS, COST_MICROS, TRACE_ID, CREATED_AT)
                .values(id, application.tenantId(), application.workspaceId(), application.id(), release.id(),
                        RunStatus.SUCCEEDED.name(), providerId, JSONB.valueOf(input.toString()),
                        JSONB.valueOf(result.output().toString()), latencyMs, result.promptTokens(),
                        result.completionTokens(), result.costMicros(), traceId, now)
                .execute();
        return findById(application.workspaceId(), id);
    }

    @Override
    public RunRecord insertFailure(
            AiApplication application,
            ReleaseBundle release,
            String providerId,
            JsonNode input,
            long latencyMs,
            String traceId,
            String failureCategory,
            String failureMessage) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(RUN)
                .columns(ID, TENANT_ID, WORKSPACE_ID, APPLICATION_ID, RELEASE_ID, STATUS, PROVIDER_ID,
                        INPUT, OUTPUT, LATENCY_MS, PROMPT_TOKENS, COMPLETION_TOKENS, COST_MICROS, TRACE_ID,
                        FAILURE_CATEGORY, FAILURE_MESSAGE, CREATED_AT)
                .values(id, application.tenantId(), application.workspaceId(), application.id(), release.id(),
                        RunStatus.FAILED.name(), providerId, JSONB.valueOf(input.toString()), JSONB.valueOf("{}"),
                        latencyMs, 0, 0, 0L, traceId, failureCategory, failureMessage, now)
                .execute();
        return findById(application.workspaceId(), id);
    }

    @Override
    public UsageSummary summarize(UUID workspaceId) {
        var record = sql.select(
                        org.jooq.impl.DSL.count(),
                        org.jooq.impl.DSL.count().filterWhere(STATUS.eq(RunStatus.SUCCEEDED.name())),
                        org.jooq.impl.DSL.count().filterWhere(STATUS.eq(RunStatus.FAILED.name())),
                        org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(PROMPT_TOKENS), 0),
                        org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(COMPLETION_TOKENS), 0),
                        org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(COST_MICROS), 0L),
                        org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.avg(LATENCY_MS), BigDecimal.ZERO))
                .from(RUN).where(WORKSPACE_ID.eq(workspaceId)).fetchOne();
        long runs = record.get(0, Number.class).longValue();
        long successful = record.get(1, Number.class).longValue();
        long failed = record.get(2, Number.class).longValue();
        long prompt = record.get(3, Number.class).longValue();
        long completion = record.get(4, Number.class).longValue();
        long cost = record.get(5, Number.class).longValue();
        double latency = record.get(6, Number.class).doubleValue();
        return new UsageSummary(runs, successful, failed, prompt, completion, prompt + completion, cost, latency);
    }

    private RunRecord findById(UUID workspaceId, UUID id) {
        return sql.select(ID, TENANT_ID, WORKSPACE_ID, APPLICATION_ID, RELEASE_ID, STATUS, PROVIDER_ID,
                        INPUT, OUTPUT, LATENCY_MS, PROMPT_TOKENS, COMPLETION_TOKENS, COST_MICROS, TRACE_ID,
                        FAILURE_CATEGORY, FAILURE_MESSAGE, CREATED_AT)
                .from(RUN)
                .where(WORKSPACE_ID.eq(workspaceId).and(ID.eq(id)))
                .fetchOptional(this::map)
                .orElseThrow();
    }

    private RunRecord map(Record record) {
        try {
            return new RunRecord(
                    record.get(ID), record.get(TENANT_ID), record.get(WORKSPACE_ID), record.get(APPLICATION_ID),
                    record.get(RELEASE_ID), RunStatus.valueOf(record.get(STATUS)), record.get(PROVIDER_ID),
                    json.readTree(record.get(INPUT).data()), json.readTree(record.get(OUTPUT).data()),
                    record.get(LATENCY_MS), record.get(PROMPT_TOKENS), record.get(COMPLETION_TOKENS),
                    record.get(COST_MICROS), record.get(TRACE_ID), record.get(FAILURE_CATEGORY),
                    record.get(FAILURE_MESSAGE), record.get(CREATED_AT));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored run payload is invalid JSON.", exception);
        }
    }
}
