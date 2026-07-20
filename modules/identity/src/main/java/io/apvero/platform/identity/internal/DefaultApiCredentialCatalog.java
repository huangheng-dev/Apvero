package io.apvero.platform.identity.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import io.apvero.platform.identity.ApiCredential;
import io.apvero.platform.identity.ApiCredentialCatalog;
import io.apvero.platform.identity.IssuedApiCredential;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultApiCredentialCatalog implements ApiCredentialCatalog {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ALLOWED_SCOPES = Set.of("read", "write", "admin");
    private final DSLContext sql;
    private final WorkspaceScopeCatalog workspaces;

    public DefaultApiCredentialCatalog(DSLContext sql, WorkspaceScopeCatalog workspaces) {
        this.sql = sql;
        this.workspaces = workspaces;
    }

    @Override
    public List<ApiCredential> list(UUID workspaceId) {
        return sql.select(
                        field("id", UUID.class), field("tenant_id", UUID.class), field("workspace_id", UUID.class),
                        field("name", String.class), field("key_prefix", String.class), field("scopes", String[].class),
                        field("status", String.class), field("expires_at", OffsetDateTime.class),
                        field("last_used_at", OffsetDateTime.class), field("created_at", OffsetDateTime.class))
                .from(table("api_credential"))
                .where(field("workspace_id", UUID.class).eq(workspaceId))
                .orderBy(field("created_at").desc())
                .fetch(record -> new ApiCredential(
                        record.value1(), record.value2(), record.value3(), record.value4(), record.value5(),
                        Set.of(record.value6()), record.value7(), record.value8(), record.value9(), record.value10()));
    }

    @Override
    @Transactional
    public IssuedApiCredential issue(UUID workspaceId, String name, Set<String> scopes, OffsetDateTime expiresAt) {
        if (name == null || name.isBlank() || name.length() > 160) throw new IllegalArgumentException("API key name is required and must not exceed 160 characters.");
        if (scopes == null || scopes.isEmpty() || !ALLOWED_SCOPES.containsAll(scopes)) throw new IllegalArgumentException("API key scopes must contain only read, write, or admin.");
        WorkspaceScope scope = workspaces.require(workspaceId);
        byte[] material = new byte[32];
        RANDOM.nextBytes(material);
        String plaintext = "apv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(table("api_credential"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("name"), field("key_prefix"),
                        field("verifier"), field("scopes"), field("status"), field("expires_at"), field("created_at"))
                .values(id, scope.tenantId(), workspaceId, name.trim(), plaintext.substring(0, 12),
                        CredentialVerifier.digest(plaintext), scopes.toArray(String[]::new), "ACTIVE", expiresAt, now)
                .execute();
        ApiCredential credential = list(workspaceId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        return new IssuedApiCredential(credential, plaintext);
    }

    @Override
    @Transactional
    public void revoke(UUID workspaceId, UUID credentialId) {
        int changed = sql.update(table("api_credential"))
                .set(field("status", String.class), "REVOKED")
                .where(field("workspace_id", UUID.class).eq(workspaceId).and(field("id", UUID.class).eq(credentialId)))
                .execute();
        if (changed == 0) throw new IllegalArgumentException("Unknown API key.");
    }
}
