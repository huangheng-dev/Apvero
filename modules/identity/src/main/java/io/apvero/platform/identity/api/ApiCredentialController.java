package io.apvero.platform.identity.api;

import io.apvero.platform.identity.ApiCredential;
import io.apvero.platform.identity.ApiCredentialCatalog;
import io.apvero.platform.identity.IssuedApiCredential;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/api-keys")
final class ApiCredentialController {
    private final ApiCredentialCatalog credentials;

    ApiCredentialController(ApiCredentialCatalog credentials) {
        this.credentials = credentials;
    }

    @GetMapping
    List<ApiCredential> list(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return credentials.list(workspaceId);
    }

    @PostMapping
    ResponseEntity<IssuedApiCredential> issue(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @Valid @RequestBody IssueRequest request) {
        return ResponseEntity.status(201).body(credentials.issue(workspaceId, request.name(), request.scopes(), request.expiresAt()));
    }

    @DeleteMapping("/{credentialId}")
    ResponseEntity<Void> revoke(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID credentialId) {
        credentials.revoke(workspaceId, credentialId);
        return ResponseEntity.noContent().build();
    }

    record IssueRequest(
            @NotBlank @Size(max = 160) String name,
            @NotEmpty Set<String> scopes,
            OffsetDateTime expiresAt) {}
}
