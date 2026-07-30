package io.apvero.platform.knowledge.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.RetrievalPolicyVersionCatalog;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RetrievalPolicyVersionControllerTest {
    @Test
    void nullJsonRequestReachesStablePolicyValidation() {
        RetrievalPolicyVersionCatalog policies = mock(RetrievalPolicyVersionCatalog.class);
        RetrievalPolicyVersionController controller = new RetrievalPolicyVersionController(policies);
        UUID workspaceId = UUID.randomUUID();
        KnowledgeException expected = new KnowledgeException(
                "APVERO_KNOWLEDGE_RETRIEVAL_POLICY_REQUEST_INVALID",
                KnowledgeException.Category.BAD_REQUEST);
        when(policies.publish(eq(workspaceId), isNull(), any())).thenThrow(expected);

        assertThatThrownBy(() -> controller.publish(
                        workspaceId, null, new MockHttpServletRequest()))
                .isSameAs(expected);
        verify(policies).publish(eq(workspaceId), isNull(), any());
    }

    @Test
    void invalidOverlapUsesStableKnowledgeError() {
        RetrievalPolicyVersionController controller =
                new RetrievalPolicyVersionController(mock(RetrievalPolicyVersionCatalog.class));
        var request = new RetrievalPolicyVersionController.PublishRequest(
                "support", "1.0.0", 8, 4096, java.math.BigDecimal.ONE, "INVALID");

        assertThatThrownBy(() -> controller.publish(
                        UUID.randomUUID(), request, new MockHttpServletRequest()))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_RETRIEVAL_POLICY_REQUEST_INVALID");
    }
}
