package io.apvero.platform.governance;

import java.util.List;
import java.util.UUID;

public interface SecretReferenceCatalog {
    List<SecretReference> list(UUID workspaceId);
    SecretReference create(UUID workspaceId, String name, String environmentVariable);
    SecretReference get(UUID workspaceId, UUID secretReferenceId);
    ResolvedSecret resolve(UUID workspaceId, UUID secretReferenceId);
}
