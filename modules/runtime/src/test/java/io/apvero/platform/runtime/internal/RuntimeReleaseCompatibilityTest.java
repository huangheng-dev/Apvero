package io.apvero.platform.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleasePurpose;
import io.apvero.platform.release.ReleaseStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RuntimeReleaseCompatibilityTest {
    private final JsonMapper json = new JsonMapper();
    private final RuntimeReleaseCompatibility compatibility =
            new RuntimeReleaseCompatibility();

    @Test
    void normalizesOnlyTheHistoricalZeroSemanticReferencesInMemory()
            throws Exception {
        ReleaseBundle stored = release(
                """
                {
                  "schemaVersion":"1.0",
                  "modelRouteVersion":"local-deterministic@1.0.0",
                  "promptVersion":"prompt@2.0.0"
                }
                """);

        ReleaseBundle execution = compatibility.forExecution(stored);

        assertThat(execution).isNotSameAs(stored);
        assertThat(execution.id()).isEqualTo(stored.id());
        assertThat(execution.artifactDigest()).isEqualTo(stored.artifactDigest());
        assertThat(execution.manifest().path("modelRouteVersion").stringValue())
                .isEqualTo("local-deterministic@1");
        assertThat(execution.manifest().path("promptVersion").stringValue())
                .isEqualTo("prompt@2");
        assertThat(stored.manifest().path("modelRouteVersion").stringValue())
                .isEqualTo("local-deterministic@1.0.0");
    }

    @Test
    void neverRewritesManifestElevenOrAmbiguousLegacyVersions() throws Exception {
        ReleaseBundle explicit = release(
                """
                {
                  "schemaVersion":"1.1",
                  "modelRouteVersion":"local-deterministic@1",
                  "promptVersion":"prompt@2"
                }
                """);
        ReleaseBundle ambiguous = release(
                """
                {
                  "schemaVersion":"1.0",
                  "modelRouteVersion":"local-deterministic@1.2.3",
                  "promptVersion":"prompt@2.1.0"
                }
                """);

        assertThat(compatibility.forExecution(explicit)).isSameAs(explicit);
        assertThat(compatibility.forExecution(ambiguous)).isSameAs(ambiguous);
    }

    private ReleaseBundle release(String manifest) throws Exception {
        return new ReleaseBundle(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "1.0.0",
                "a".repeat(64),
                json.readTree(manifest),
                ReleaseStatus.RELEASED,
                ReleasePurpose.PRODUCTION,
                null,
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
