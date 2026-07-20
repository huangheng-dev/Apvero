package io.apvero.platform.identity.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

@Service
final class JooqWorkspaceScopeCatalog implements WorkspaceScopeCatalog {
    private final DSLContext sql;

    JooqWorkspaceScopeCatalog(DSLContext sql) {
        this.sql = sql;
    }

    @Override
    public WorkspaceScope require(UUID workspaceId) {
        return sql.select(field("tenant_id", UUID.class))
                .from(table("workspace"))
                .where(field("id", UUID.class).eq(workspaceId))
                .fetchOptional(record -> new WorkspaceScope(record.value1(), workspaceId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown workspace."));
    }
}
