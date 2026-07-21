package io.apvero.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DeliveryStagesTest {
    private static final List<String> EXPECTED_STAGE_IDS = List.of("P0", "P1", "P2", "P3", "P4", "P5", "P6", "P7");

    @Test
    void deliveryOrderAndBilingualRoadmapsStayAligned() throws IOException {
        Map<String, Object> document = loadDeliveryStages();
        List<Map<String, Object>> stages = castList(document.get("stages"));

        assertThat(stages).extracting(stage -> stage.get("id")).containsExactlyElementsOf(EXPECTED_STAGE_IDS);
        assertThat(document.get("currentStage")).isEqualTo("P2");
        assertThat(stages.stream().filter(stage -> stage.get("id").equals(document.get("currentStage"))).findFirst())
                .hasValueSatisfying(stage -> assertThat(stage.get("status")).isEqualTo("in-progress"));

        for (int index = 0; index < stages.size(); index++) {
            List<String> expectedDependencies = index == 0 ? List.of() : List.of("P" + (index - 1));
            assertThat(castList(stages.get(index).get("dependsOn"))).containsExactlyElementsOf(expectedDependencies);
        }

        assertThat(stageHeadings(repositoryFile("docs/en/roadmap.md"))).containsExactlyElementsOf(EXPECTED_STAGE_IDS);
        assertThat(stageHeadings(repositoryFile("docs/zh-CN/roadmap.md"))).containsExactlyElementsOf(EXPECTED_STAGE_IDS);
    }

    private Map<String, Object> loadDeliveryStages() throws IOException {
        String yaml = Files.readString(repositoryFile("architecture/delivery-stages.yaml"), StandardCharsets.UTF_8);
        return new Yaml().load(yaml);
    }

    private List<String> stageHeadings(Path roadmap) throws IOException {
        return Files.readAllLines(roadmap, StandardCharsets.UTF_8).stream()
                .filter(line -> line.matches("## P[0-7].*"))
                .map(line -> line.substring(3, 5))
                .toList();
    }

    private Path repositoryFile(String relativePath) {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("AGENTS.md"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate the Apvero repository root.");
        return current.resolve(relativePath);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> castList(Object value) {
        return (List<T>) value;
    }
}
