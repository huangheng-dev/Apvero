package io.apvero.platform.governance.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class PolicyDecisionAudit {
    private final DSLContext sql;

    PolicyDecisionAudit(DSLContext sql) {
        this.sql = sql;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void denied(UUID tenantId, UUID workspaceId, UUID applicationId, String actorId, String traceId, String reasonCode) {
        sql.insertInto(table("audit_event"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("occurred_at"),
                        field("actor_id"), field("action"), field("resource_type"), field("resource_id"),
                        field("outcome"), field("trace_id"), field("details"))
                .values(UUID.randomUUID(), tenantId, workspaceId, OffsetDateTime.now(ZoneOffset.UTC), actorId,
                        "EXECUTION_ADMISSION", "application", applicationId.toString(), "DENIED", traceId,
                        JSONB.valueOf("{\"reasonCode\":\"" + reasonCode + "\"}"))
                .execute();
    }
}
