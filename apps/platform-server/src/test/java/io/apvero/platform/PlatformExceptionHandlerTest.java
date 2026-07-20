package io.apvero.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlatformExceptionHandlerTest {

    @Test
    void usesAStableProductOwnedProblemUrn() {
        var detail = new PlatformExceptionHandler().invalidRequest(new IllegalArgumentException("invalid"));

        assertThat(detail.getType().toString()).isEqualTo("urn:apvero:problem:apvero_invalid_request");
        assertThat(detail.getProperties()).containsEntry("code", "APVERO_INVALID_REQUEST");
        assertThat(detail.getProperties()).containsKey("timestamp");
    }
}
