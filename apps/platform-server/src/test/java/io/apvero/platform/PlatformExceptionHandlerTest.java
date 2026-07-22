package io.apvero.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.apvero.platform.knowledge.KnowledgeDisabledException;
import io.apvero.platform.knowledge.KnowledgeException;
import org.junit.jupiter.api.Test;

class PlatformExceptionHandlerTest {

    @Test
    void usesAStableProductOwnedProblemUrn() {
        var detail = new PlatformExceptionHandler().invalidRequest(new IllegalArgumentException("invalid"));

        assertThat(detail.getType().toString()).isEqualTo("urn:apvero:problem:apvero_invalid_request");
        assertThat(detail.getProperties()).containsEntry("code", "APVERO_INVALID_REQUEST");
        assertThat(detail.getProperties()).containsKey("timestamp");
    }

    @Test
    void localizesKnowledgeDisabledByStableCodeInsteadOfBackendMessage() {
        var detail = new PlatformExceptionHandler().knowledgeDisabled(new KnowledgeDisabledException());

        assertThat(detail.getStatus()).isEqualTo(503);
        assertThat(detail.getDetail()).isEqualTo(KnowledgeDisabledException.CODE);
        assertThat(detail.getProperties()).containsEntry("code", KnowledgeDisabledException.CODE);
    }

    @Test
    void mapsKnowledgeFailuresByStableCategoryAndCode() {
        var detail = new PlatformExceptionHandler().knowledgeProblem(new KnowledgeException(
                "APVERO_KNOWLEDGE_CONTENT_TOO_LARGE", KnowledgeException.Category.CONTENT_TOO_LARGE));

        assertThat(detail.getStatus()).isEqualTo(413);
        assertThat(detail.getDetail()).isEqualTo("APVERO_KNOWLEDGE_CONTENT_TOO_LARGE");
        assertThat(detail.getProperties())
                .containsEntry("code", "APVERO_KNOWLEDGE_CONTENT_TOO_LARGE");
    }
}
