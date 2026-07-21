package io.apvero.platform.governance.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import io.apvero.platform.governance.ResolvedSecret;
import io.apvero.platform.governance.SecretReference;
import io.apvero.platform.governance.SecretReferenceCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultSecretReferenceCatalog implements SecretReferenceCatalog {
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{1,159}$");
    private final DSLContext sql;
    private final WorkspaceScopeCatalog workspaces;
    private final Environment environment;

    public DefaultSecretReferenceCatalog(DSLContext sql, WorkspaceScopeCatalog workspaces, Environment environment) {
        this.sql = sql;
        this.workspaces = workspaces;
        this.environment = environment;
    }

    @Override
    public List<SecretReference> list(UUID workspaceId) {
        return sql.select(
                        field("id", UUID.class), field("tenant_id", UUID.class), field("workspace_id", UUID.class),
                        field("name", String.class), field("kind", String.class), field("locator", String.class),
                        field("status", String.class), field("rotated_at", OffsetDateTime.class),
                        field("created_at", OffsetDateTime.class), field("updated_at", OffsetDateTime.class))
                .from(table("secret_reference"))
                .where(field("workspace_id", UUID.class).eq(workspaceId))
                .orderBy(field("updated_at").desc())
                .fetch(record -> new SecretReference(record.value1(), record.value2(), record.value3(), record.value4(),
                        record.value5(), record.value6(), record.value7(), record.value8(), record.value9(), record.value10()));
    }

    @Override
    @Transactional
    public SecretReference create(UUID workspaceId, String name, String environmentVariable) {
        if (name == null || name.isBlank() || name.length() > 160) throw new IllegalArgumentException("Secret reference name is required and must not exceed 160 characters.");
        if (environmentVariable == null || !ENV_NAME.matcher(environmentVariable).matches()) throw new IllegalArgumentException("Environment variable locator is invalid.");
        WorkspaceScope scope = workspaces.require(workspaceId);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(table("secret_reference"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("name"), field("kind"),
                        field("locator"), field("status"), field("created_at"), field("updated_at"))
                .values(id, scope.tenantId(), workspaceId, name.trim(), "ENVIRONMENT", environmentVariable,
                        "ACTIVE", now, now)
                .execute();
        return get(workspaceId, id);
    }

    @Override
    public SecretReference get(UUID workspaceId, UUID secretReferenceId) {
        return list(workspaceId).stream().filter(item -> item.id().equals(secretReferenceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown secret reference."));
    }

    @Override
    public ResolvedSecret resolve(UUID workspaceId, UUID secretReferenceId) {
        SecretReference reference = get(workspaceId, secretReferenceId);
        if (!"ACTIVE".equals(reference.status())) throw new IllegalArgumentException("Secret reference is not active.");
        String value = environment.getProperty(reference.locator());
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Referenced environment secret is unavailable.");
        return new ResolvedSecret(value.toCharArray());
    }

    @Override
    public boolean isAvailable(UUID workspaceId, UUID secretReferenceId) {
        SecretReference reference = get(workspaceId, secretReferenceId);
        String value = environment.getProperty(reference.locator());
        return "ACTIVE".equals(reference.status()) && value != null && !value.isBlank();
    }
}
