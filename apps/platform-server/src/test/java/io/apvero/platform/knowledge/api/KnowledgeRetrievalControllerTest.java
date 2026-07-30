package io.apvero.platform.knowledge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.knowledge.KnowledgeRetrieval;
import io.apvero.platform.knowledge.KnowledgeRetrievalResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class KnowledgeRetrievalControllerTest {
    @Test
    void mapsTheCommittedRequestShapeToThePublicBoundary() {
        KnowledgeRetrieval retrieval = mock(KnowledgeRetrieval.class);
        KnowledgeRetrievalController controller = new KnowledgeRetrievalController(retrieval);
        UUID workspaceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        var request = new KnowledgeRetrievalController.RetrievalRequest(
                versionId, policyId, "Where is the policy?");
        var expected = new KnowledgeRetrievalResult(
                KnowledgeRetrievalResult.Status.NO_EVIDENCE,
                versionId,
                policyId,
                "sha256:" + "a".repeat(64),
                List.of(),
                12);
        when(retrieval.retrieve(
                        eq(workspaceId),
                        any(),
                        eq(versionId),
                        eq(policyId),
                        eq("Where is the policy?")))
                .thenReturn(expected);

        assertThat(controller.retrieve(
                        workspaceId, request, new MockHttpServletRequest()))
                .isSameAs(expected);
        verify(retrieval).retrieve(
                eq(workspaceId),
                any(),
                eq(versionId),
                eq(policyId),
                eq("Where is the policy?"));
    }

    @Test
    void nullJsonRequestReachesStableBoundaryValidationWithoutControllerDrift() {
        KnowledgeRetrieval retrieval = mock(KnowledgeRetrieval.class);
        KnowledgeRetrievalController controller = new KnowledgeRetrievalController(retrieval);
        UUID workspaceId = UUID.randomUUID();

        controller.retrieve(workspaceId, null, new MockHttpServletRequest());

        verify(retrieval).retrieve(
                eq(workspaceId), any(), isNull(), isNull(), isNull());
    }
}
