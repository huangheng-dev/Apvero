package io.apvero.platform.release.internal;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import io.apvero.platform.release.ReleaseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
final class ReleaseManifestValidator {
    private static final String MANIFEST_1_0_ID =
            "https://schemas.apvero.dev/release-bundle-manifest/1.0.0";
    private static final String MANIFEST_1_1_ID =
            "https://schemas.apvero.dev/release-bundle-manifest/1.1.0";
    private static final Map<String, String> SCHEMA_IDS = Map.of(
            "1.0", MANIFEST_1_0_ID,
            "1.1", MANIFEST_1_1_ID);

    private final SchemaRegistry schemas;

    ReleaseManifestValidator() {
        Map<String, String> allowlistedSchemas = Map.of(
                MANIFEST_1_0_ID,
                resource("/apvero/contracts/release-bundle-manifest.schema.json"),
                MANIFEST_1_1_ID,
                resource("/apvero/contracts/release-bundle-manifest.v1.1.schema.json"));
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
                .build();
        this.schemas = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder
                        .schemaRegistryConfig(config)
                        .schemas(allowlistedSchemas));
    }

    void validate(JsonNode manifest) {
        if (manifest == null || !manifest.isObject()) {
            throw problem("APVERO_RELEASE_MANIFEST_INVALID");
        }
        JsonNode schemaVersion = manifest.get("schemaVersion");
        String schemaId = schemaVersion != null && schemaVersion.isString()
                ? SCHEMA_IDS.get(schemaVersion.stringValue())
                : null;
        if (schemaId == null) {
            throw problem("APVERO_RELEASE_MANIFEST_UNSUPPORTED");
        }
        Schema schema = schemas.getSchema(SchemaLocation.of(schemaId));
        List<com.networknt.schema.Error> errors = schema.validate(
                manifest.toString(),
                InputFormat.JSON,
                execution -> execution.executionConfig(
                        configuration -> configuration.formatAssertionsEnabled(true)));
        if (!errors.isEmpty()) {
            throw problem("APVERO_RELEASE_MANIFEST_INVALID");
        }
    }

    private static String resource(String path) {
        try (InputStream input = ReleaseManifestValidator.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Packaged Release manifest schema is missing.");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Packaged Release manifest schema cannot be read.", exception);
        }
    }

    private static ReleaseException problem(String code) {
        return new ReleaseException(code, ReleaseException.Category.BAD_REQUEST);
    }
}
