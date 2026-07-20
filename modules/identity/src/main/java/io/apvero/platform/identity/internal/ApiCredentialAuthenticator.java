package io.apvero.platform.identity.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

@Component
final class ApiCredentialAuthenticator {
    record AuthenticatedCredential(UUID workspaceId, String name, Set<String> scopes) {}

    private final DSLContext sql;

    ApiCredentialAuthenticator(DSLContext sql) {
        this.sql = sql;
    }

    AuthenticatedCredential authenticate(String plaintext) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var result = sql.select(field("id", UUID.class), field("workspace_id", UUID.class), field("name", String.class),
                        field("scopes", String[].class))
                .from(table("api_credential"))
                .where(field("verifier", String.class).eq(CredentialVerifier.digest(plaintext))
                        .and(field("status", String.class).eq("ACTIVE"))
                        .and(field("expires_at", OffsetDateTime.class).isNull().or(field("expires_at", OffsetDateTime.class).gt(now))))
                .fetchOptional();
        if (result.isEmpty()) return null;
        var record = result.get();
        sql.update(table("api_credential"))
                .set(field("last_used_at", OffsetDateTime.class), now)
                .where(field("id", UUID.class).eq(record.value1()))
                .execute();
        return new AuthenticatedCredential(record.value2(), record.value3(), Set.of(record.value4()));
    }
}
