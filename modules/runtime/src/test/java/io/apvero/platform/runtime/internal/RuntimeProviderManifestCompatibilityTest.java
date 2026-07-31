package io.apvero.platform.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.apvero.platform.capability.CapabilityCatalog;
import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.release.ReleasePurpose;
import io.apvero.platform.release.ReleaseStatus;
import io.apvero.platform.runtime.adapters.springai.SpringAiOpenAiCompatibleProvider;
import io.apvero.platform.runtime.ProviderExecutionException;
import io.apvero.platform.runtime.ProviderRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RuntimeProviderManifestCompatibilityTest {
    private final JsonMapper json = new JsonMapper();
    private final DeterministicLocalProvider local = new DeterministicLocalProvider(json);
    private final SpringAiOpenAiCompatibleProvider remote =
            new SpringAiOpenAiCompatibleProvider(
                    mock(CapabilityCatalog.class), json, false);

    @Test
    void providersAcceptLegacyChatAndRagButRagExecutionRequiresGroundingContext()
            throws Exception {
        assertThat(local.supports(release("""
                {"schemaVersion":"1.0","modelRouteVersion":"local-deterministic@1"}
                """))).isTrue();
        assertThat(local.supports(release("""
                {"schemaVersion":"1.1","runtimeMode":"CHAT","modelRouteVersion":"local-deterministic@1"}
                """))).isTrue();
        assertThat(local.supports(release("""
                {"schemaVersion":"1.1","runtimeMode":"RAG","modelRouteVersion":"local-deterministic@1"}
                """))).isTrue();

        assertThat(remote.supports(release("""
                {"schemaVersion":"1.0","modelRouteVersion":"remote@1"}
                """))).isTrue();
        assertThat(remote.supports(release("""
                {"schemaVersion":"1.1","runtimeMode":"CHAT","modelRouteVersion":"remote@1"}
                """))).isTrue();
        assertThat(remote.supports(release("""
                {"schemaVersion":"1.1","runtimeMode":"RAG","modelRouteVersion":"remote@1"}
                """))).isTrue();

        ReleaseBundle rag = release("""
                {"schemaVersion":"1.1","runtimeMode":"RAG","modelRouteVersion":"local-deterministic@1"}
                """);
        assertThatThrownBy(() -> local.execute(new ProviderRequest(
                        rag, json.createObjectNode().put("message", "question"), "trace")))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting("code")
                .isEqualTo("APVERO_RUNTIME_GROUNDING_CONTEXT_REQUIRED");
    }

    private ReleaseBundle release(String manifest) throws Exception {
        JsonNode parsed = json.readTree(manifest);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ReleaseBundle(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "1.0.0",
                "0".repeat(64),
                parsed,
                ReleaseStatus.RELEASED,
                ReleasePurpose.PRODUCTION,
                null,
                now);
    }
}
