package io.apvero.platform.application.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import io.apvero.platform.application.AiApplication;
import io.apvero.platform.application.ApplicationStatus;
import io.apvero.platform.application.CreateApplicationCommand;
import io.apvero.platform.application.RuntimeMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
public class JooqApplicationRepository implements ApplicationRepository {
    private static final Table<?> APPLICATION = table("ai_application");
    private static final Table<?> WORKSPACE = table("workspace");
    private static final Field<UUID> ID = field("id", UUID.class);
    private static final Field<UUID> TENANT_ID = field("tenant_id", UUID.class);
    private static final Field<UUID> WORKSPACE_ID = field("workspace_id", UUID.class);
    private static final Field<String> SLUG = field("slug", String.class);
    private static final Field<String> NAME = field("name", String.class);
    private static final Field<String> DESCRIPTION = field("description", String.class);
    private static final Field<String> RUNTIME_MODE = field("runtime_mode", String.class);
    private static final Field<String> STATUS = field("status", String.class);
    private static final Field<UUID> DRAFT_MODEL_ROUTE_ID = field("draft_model_route_id", UUID.class);
    private static final Field<UUID> DRAFT_PROMPT_VERSION_ID = field("draft_prompt_version_id", UUID.class);
    private static final Field<Long> VERSION = field("version", Long.class);
    private static final Field<OffsetDateTime> CREATED_AT = field("created_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> UPDATED_AT = field("updated_at", OffsetDateTime.class);

    private final DSLContext sql;

    public JooqApplicationRepository(DSLContext sql) {
        this.sql = sql;
    }

    @Override
    public List<AiApplication> findAll(UUID workspaceId) {
        return sql.select(ID, TENANT_ID, WORKSPACE_ID, SLUG, NAME, DESCRIPTION, RUNTIME_MODE,
                        STATUS, DRAFT_MODEL_ROUTE_ID, DRAFT_PROMPT_VERSION_ID, VERSION, CREATED_AT, UPDATED_AT)
                .from(APPLICATION)
                .where(WORKSPACE_ID.eq(workspaceId))
                .orderBy(UPDATED_AT.desc())
                .fetch(this::map);
    }

    @Override
    public Optional<AiApplication> findById(UUID workspaceId, UUID applicationId) {
        return sql.select(ID, TENANT_ID, WORKSPACE_ID, SLUG, NAME, DESCRIPTION, RUNTIME_MODE,
                        STATUS, DRAFT_MODEL_ROUTE_ID, DRAFT_PROMPT_VERSION_ID, VERSION, CREATED_AT, UPDATED_AT)
                .from(APPLICATION)
                .where(WORKSPACE_ID.eq(workspaceId).and(ID.eq(applicationId)))
                .fetchOptional(this::map);
    }

    @Override
    public AiApplication insert(UUID workspaceId, CreateApplicationCommand command) {
        UUID tenantId = sql.select(field("tenant_id", UUID.class))
                .from(WORKSPACE)
                .where(field("id", UUID.class).eq(workspaceId))
                .fetchOptional(field("tenant_id", UUID.class))
                .orElseThrow(() -> new IllegalArgumentException("Unknown workspace."));
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(APPLICATION)
                .columns(ID, TENANT_ID, WORKSPACE_ID, SLUG, NAME, DESCRIPTION, RUNTIME_MODE,
                        STATUS, VERSION, CREATED_AT, UPDATED_AT)
                .values(id, tenantId, workspaceId, command.slug(), command.name(), command.description(),
                        command.runtimeMode().name(), ApplicationStatus.DRAFT.name(), 1L, now, now)
                .execute();
        return findById(workspaceId, id).orElseThrow();
    }

    @Override
    public AiApplication bindDraft(UUID workspaceId, UUID applicationId, UUID modelRouteId, UUID promptVersionId) {
        int changed = sql.update(APPLICATION)
                .set(DRAFT_MODEL_ROUTE_ID, modelRouteId)
                .set(DRAFT_PROMPT_VERSION_ID, promptVersionId)
                .set(VERSION, VERSION.plus(1L))
                .set(UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(WORKSPACE_ID.eq(workspaceId).and(ID.eq(applicationId)))
                .execute();
        if (changed == 0) throw new IllegalArgumentException("Unknown AI Application.");
        return findById(workspaceId, applicationId).orElseThrow();
    }

    private AiApplication map(Record record) {
        return new AiApplication(
                record.get(ID), record.get(TENANT_ID), record.get(WORKSPACE_ID), record.get(SLUG),
                record.get(NAME), record.get(DESCRIPTION), RuntimeMode.valueOf(record.get(RUNTIME_MODE)),
                ApplicationStatus.valueOf(record.get(STATUS)), record.get(DRAFT_MODEL_ROUTE_ID),
                record.get(DRAFT_PROMPT_VERSION_ID), record.get(VERSION), record.get(CREATED_AT),
                record.get(UPDATED_AT));
    }
}
