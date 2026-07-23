package io.apvero.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class KnowledgeDeploymentBoundaryTest {

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    @Test
    void workerHasNoHostPortAndRunsOnlyOnTheInternalProfile() throws IOException {
        String compose = read("deploy/compose/compose.yaml");
        String worker = section(compose, "  ai-worker:\n", "  console:\n");

        assertThat(worker)
                .contains("profiles: [knowledge]")
                .contains("read_only: true")
                .contains("cap_drop: [ALL]")
                .contains("no-new-privileges:true")
                .contains("- knowledge-internal")
                .doesNotContain("ports:");
        assertThat(compose)
                .contains(
                        "knowledge-internal:\n"
                                + "    name: ${APVERO_KNOWLEDGE_NETWORK_NAME:-apvero_knowledge_internal}\n"
                                + "    internal: true");
    }

    @Test
    void consoleCannotProxyOrDependOnTheWorker() throws IOException {
        String nginx = read("deploy/nginx/default.conf");
        String compose = read("deploy/compose/compose.yaml");
        String console = section(compose, "  console:\n", "\nnetworks:\n");

        assertThat(nginx)
                .doesNotContain("location /worker/")
                .doesNotContain("proxy_pass http://ai-worker");
        assertThat(console).doesNotContain("ai-worker:");
    }

    @Test
    void workerContainerIsUnprivilegedAndHasNoRuntimeCredentials() throws IOException {
        String dockerfile = read("apps/ai-worker/Dockerfile");
        String worker = section(
                read("deploy/compose/compose.yaml"),
                "  ai-worker:\n",
                "  console:\n");

        assertThat(dockerfile).contains("USER 10002:10002");
        assertThat(worker)
                .doesNotContain("APVERO_DB_")
                .doesNotContain("OPENAI_API_KEY")
                .doesNotContain("DEEPSEEK_API_KEY");
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(REPOSITORY_ROOT.resolve(relativePath), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private String section(String content, String startMarker, String endMarker) {
        int start = content.indexOf(startMarker);
        int end = content.indexOf(endMarker, start + startMarker.length());
        assertThat(start).as("start marker %s", startMarker).isGreaterThanOrEqualTo(0);
        assertThat(end).as("end marker %s", endMarker).isGreaterThan(start);
        return content.substring(start, end);
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("AGENTS.md"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("APVERO_REPOSITORY_ROOT_NOT_FOUND");
        }
        return current;
    }
}
