package io.apvero.platform.knowledge.api;

import io.apvero.platform.knowledge.CreateRetrievalPolicyVersionCommand;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.RetrievalPolicyOverlapHandling;
import io.apvero.platform.knowledge.RetrievalPolicyVersion;
import io.apvero.platform.knowledge.RetrievalPolicyVersionCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
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
@RequestMapping("/api/v1/retrieval-policy-versions")
final class RetrievalPolicyVersionController {
    private final RetrievalPolicyVersionCatalog policies;

    RetrievalPolicyVersionController(RetrievalPolicyVersionCatalog policies) {
        this.policies = policies;
    }

    @GetMapping
    List<RetrievalPolicyVersion> list(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return policies.list(workspaceId);
    }

    @PostMapping
    ResponseEntity<RetrievalPolicyVersion> publish(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @RequestBody(required = false) PublishRequest request,
            HttpServletRequest httpRequest) {
        CreateRetrievalPolicyVersionCommand command = request == null
                ? null
                : new CreateRetrievalPolicyVersionCommand(
                        request.slug(),
                        request.version(),
                        request.topK(),
                        request.maxContextTokens(),
                        request.minimumScore(),
                        overlap(request.overlapHandling()));
        return ResponseEntity.status(201).body(
                policies.publish(workspaceId, command, KnowledgeController.context(httpRequest)));
    }

    private static RetrievalPolicyOverlapHandling overlap(String value) {
        if (value == null) {
            return null;
        }
        try {
            return RetrievalPolicyOverlapHandling.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new KnowledgeException(
                    "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_REQUEST_INVALID",
                    KnowledgeException.Category.BAD_REQUEST);
        }
    }

    record PublishRequest(
            String slug,
            String version,
            Integer topK,
            Integer maxContextTokens,
            BigDecimal minimumScore,
            String overlapHandling) {}
}
