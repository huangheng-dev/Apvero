package io.apvero.platform.release.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.apvero.platform.application.AiApplication;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleaseStatus;
import io.apvero.platform.release.ReleasePurpose;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
public class JooqReleaseRepository implements ReleaseRepository {
    private static final Table<?> RELEASE = table("release_bundle");
    private static final Field<UUID> ID = field("id", UUID.class);
    private static final Field<UUID> TENANT_ID = field("tenant_id", UUID.class);
    private static final Field<UUID> WORKSPACE_ID = field("workspace_id", UUID.class);
    private static final Field<UUID> APPLICATION_ID = field("application_id", UUID.class);
    private static final Field<String> VERSION = field("version", String.class);
    private static final Field<String> DIGEST = field("artifact_digest", String.class);
    private static final Field<JSONB> MANIFEST = field("manifest", JSONB.class);
    private static final Field<String> STATUS = field("status", String.class);
    private static final Field<String> PURPOSE = field("purpose", String.class);
    private static final Field<OffsetDateTime> EXPIRES_AT = field("expires_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> CREATED_AT = field("created_at", OffsetDateTime.class);

    private final DSLContext sql;
    private final ObjectMapper json;

    public JooqReleaseRepository(DSLContext sql, ObjectMapper json) {
        this.sql = sql;
        this.json = json;
    }

    @Override
    public List<ReleaseBundle> findAll(UUID workspaceId, UUID applicationId) {
        return sql.select(ID, TENANT_ID, WORKSPACE_ID, APPLICATION_ID, VERSION, DIGEST, MANIFEST, STATUS, PURPOSE, EXPIRES_AT, CREATED_AT)
                .from(RELEASE)
                .where(WORKSPACE_ID.eq(workspaceId).and(APPLICATION_ID.eq(applicationId)).and(PURPOSE.eq(ReleasePurpose.PRODUCTION.name())))
                .orderBy(CREATED_AT.desc())
                .fetch(this::map);
    }

    @Override
    public Optional<ReleaseBundle> findById(UUID workspaceId, UUID releaseId) {
        return sql.select(ID, TENANT_ID, WORKSPACE_ID, APPLICATION_ID, VERSION, DIGEST, MANIFEST, STATUS, PURPOSE, EXPIRES_AT, CREATED_AT)
                .from(RELEASE)
                .where(WORKSPACE_ID.eq(workspaceId).and(ID.eq(releaseId)))
                .fetchOptional(this::map);
    }

    @Override
    public ReleaseBundle insert(AiApplication application, String version, String digest, JsonNode manifest,
            ReleasePurpose purpose, OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(RELEASE)
                .columns(ID, TENANT_ID, WORKSPACE_ID, APPLICATION_ID, VERSION, DIGEST, MANIFEST, STATUS, PURPOSE, EXPIRES_AT, CREATED_AT)
                .values(id, application.tenantId(), application.workspaceId(), application.id(), version, digest,
                        JSONB.valueOf(manifest.toString()), ReleaseStatus.RELEASED.name(), purpose.name(), expiresAt, now)
                .execute();
        return findById(application.workspaceId(), id).orElseThrow();
    }

    private ReleaseBundle map(Record record) {
        try {
            return new ReleaseBundle(
                    record.get(ID), record.get(TENANT_ID), record.get(WORKSPACE_ID), record.get(APPLICATION_ID),
                    record.get(VERSION), record.get(DIGEST), json.readTree(record.get(MANIFEST).data()),
                    ReleaseStatus.valueOf(record.get(STATUS)), ReleasePurpose.valueOf(record.get(PURPOSE)),
                    record.get(EXPIRES_AT), record.get(CREATED_AT));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored release manifest is invalid JSON.", exception);
        }
    }
}
