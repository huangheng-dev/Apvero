package io.apvero.platform.governance.api;

import io.apvero.platform.governance.SecretReference;
import io.apvero.platform.governance.SecretReferenceCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/secrets")
final class SecretReferenceController {
    private final SecretReferenceCatalog secrets;

    SecretReferenceController(SecretReferenceCatalog secrets) {
        this.secrets = secrets;
    }

    @GetMapping
    List<SecretReference> list(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return secrets.list(workspaceId);
    }

    @PostMapping
    ResponseEntity<SecretReference> create(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @Valid @RequestBody CreateSecretReferenceRequest request) {
        SecretReference created = secrets.create(workspaceId, request.name(), request.environmentVariable());
        return ResponseEntity.created(URI.create("/api/v1/secrets/" + created.id())).body(created);
    }

    record CreateSecretReferenceRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,159}$") String environmentVariable) {}
}
