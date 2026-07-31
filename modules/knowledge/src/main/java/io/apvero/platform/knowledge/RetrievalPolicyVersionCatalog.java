package io.apvero.platform.knowledge;

import java.util.List;
import java.util.UUID;

public interface RetrievalPolicyVersionCatalog {
    List<RetrievalPolicyVersion> list(UUID workspaceId);

    RetrievalPolicyVersion get(UUID workspaceId, UUID policyVersionId);

    RetrievalPolicyVersion getByReference(UUID workspaceId, String reference);

    boolean supportsExecution(RetrievalPolicyVersion policy);

    RetrievalPolicyVersion publish(
            UUID workspaceId,
            CreateRetrievalPolicyVersionCommand command,
            KnowledgeCommandContext context);
}
