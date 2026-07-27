package io.apvero.platform.knowledge.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeIndexBuildCatalog;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class KnowledgeIndexBuildControllerTest {
    @Test
    void nullJsonRequestReachesStableKnowledgeValidationInsteadOfDereferencingTheBody() {
        KnowledgeIndexBuildCatalog builds = mock(KnowledgeIndexBuildCatalog.class);
        KnowledgeIndexBuildController controller = new KnowledgeIndexBuildController(builds);
        UUID workspaceId = UUID.randomUUID();
        UUID indexId = UUID.randomUUID();
        KnowledgeException expected = new KnowledgeException(
                "APVERO_KNOWLEDGE_BUILD_REQUEST_INVALID",
                KnowledgeException.Category.BAD_REQUEST);
        when(builds.create(eq(workspaceId), eq(indexId), isNull(), any())).thenThrow(expected);

        assertThatThrownBy(() -> controller.createBuild(
                        workspaceId, indexId, null, new MockHttpServletRequest()))
                .isSameAs(expected);
        verify(builds).create(eq(workspaceId), eq(indexId), isNull(), any());
    }
}
