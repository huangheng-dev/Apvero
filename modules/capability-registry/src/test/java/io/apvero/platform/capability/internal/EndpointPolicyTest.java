package io.apvero.platform.capability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EndpointPolicyTest {
    @Test
    void rejectsPrivateAndCredentialBearingDestinationsByDefault() {
        EndpointPolicy policy = new EndpointPolicy(false);
        assertThatThrownBy(() -> policy.validate("http://127.0.0.1:11434/v1", "OPENAI_COMPATIBLE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("https://127.0.0.1/v1", "OPENAI_COMPATIBLE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("https://user:pass@example.com/v1", "OPENAI_COMPATIBLE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permitsExplicitPrivateDevelopmentEndpoints() {
        EndpointPolicy policy = new EndpointPolicy(true);
        assertThat(policy.validate("http://127.0.0.1:11434/v1/", "OPENAI_COMPATIBLE"))
                .isEqualTo("http://127.0.0.1:11434/v1");
    }
}
