package io.apvero.platform.knowledge.api;

import io.apvero.platform.knowledge.KnowledgeRetrieval;
import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class KnowledgeRetrievalController {
    private final KnowledgeRetrieval retrieval;

    KnowledgeRetrievalController(KnowledgeRetrieval retrieval) {
        this.retrieval = retrieval;
    }

    @PostMapping("/knowledge-retrieval-tests")
    KnowledgeRetrievalResult retrieve(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @RequestBody(required = false) RetrievalRequest request,
            HttpServletRequest httpRequest) {
        return retrieval.retrieve(
                workspaceId,
                KnowledgeController.context(httpRequest),
                request == null ? null : request.indexVersionId(),
                request == null ? null : request.retrievalPolicyVersionId(),
                request == null ? null : request.query());
    }

    record RetrievalRequest(
            UUID indexVersionId,
            UUID retrievalPolicyVersionId,
            String query) {}
}
