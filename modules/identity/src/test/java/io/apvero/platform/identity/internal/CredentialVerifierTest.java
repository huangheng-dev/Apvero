package io.apvero.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CredentialVerifierTest {
    @Test
    void producesStableNonPlaintextVerifier() {
        String verifier = CredentialVerifier.digest("apv_example-secret-material");
        assertThat(verifier).hasSize(64).doesNotContain("example-secret-material");
        assertThat(CredentialVerifier.constantTimeEquals(verifier, CredentialVerifier.digest("apv_example-secret-material"))).isTrue();
        assertThat(CredentialVerifier.constantTimeEquals(verifier, CredentialVerifier.digest("different"))).isFalse();
    }
}
