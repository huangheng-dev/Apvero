package io.apvero.platform.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ApiCredentialCatalog {
    List<ApiCredential> list(UUID workspaceId);
    IssuedApiCredential issue(UUID workspaceId, String name, Set<String> scopes, OffsetDateTime expiresAt);
    void revoke(UUID workspaceId, UUID credentialId);
}
