package io.apvero.platform.release.internal;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class ReleaseArtifactDigesterTest {
    private final JsonMapper json = new JsonMapper();
    private final ReleaseArtifactDigester digester = new ReleaseArtifactDigester(json);

    @Test
    void objectPropertyOrderDoesNotChangeDigest() throws Exception {
        var first = json.readTree("""
                {"model":{"id":"route@1.0.0","weight":1},"prompt":"prompt@1.0.0"}
                """);
        var second = json.readTree("""
                {"prompt":"prompt@1.0.0","model":{"weight":1,"id":"route@1.0.0"}}
                """);

        assertThat(digester.digest(first)).isEqualTo(digester.digest(second)).hasSize(64);
    }

    @Test
    void contentChangeProducesDifferentDigest() throws Exception {
        var first = json.readTree("{\"temperature\":0}");
        var second = json.readTree("{\"temperature\":1}");

        assertThat(digester.digest(first)).isNotEqualTo(digester.digest(second));
    }
}
