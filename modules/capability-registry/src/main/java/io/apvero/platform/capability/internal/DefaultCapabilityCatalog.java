package io.apvero.platform.capability.internal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.apvero.platform.capability.CapabilityCatalog;
import io.apvero.platform.capability.ModelDefinition;
import io.apvero.platform.capability.ModelProvider;
import io.apvero.platform.capability.ModelRoute;
import io.apvero.platform.capability.PromptAsset;
import io.apvero.platform.capability.PromptVersion;
import io.apvero.platform.capability.RuntimeConfiguration;
import io.apvero.platform.governance.ResolvedSecret;
import io.apvero.platform.governance.SecretReferenceCatalog;
import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultCapabilityCatalog implements CapabilityCatalog {
    private static final Pattern PROMPT_VARIABLE = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_]{0,63})}}", Pattern.MULTILINE);
    private final DSLContext sql;
    private final ObjectMapper json;
    private final WorkspaceScopeCatalog workspaces;
    private final SecretReferenceCatalog secrets;
    private final EndpointPolicy endpoints;

    public DefaultCapabilityCatalog(DSLContext sql, ObjectMapper json, WorkspaceScopeCatalog workspaces,
            SecretReferenceCatalog secrets, EndpointPolicy endpoints) {
        this.sql = sql;
        this.json = json;
        this.workspaces = workspaces;
        this.secrets = secrets;
        this.endpoints = endpoints;
    }

    @Override
    public List<ModelProvider> listProviders(UUID workspaceId) {
        return sql.select().from(table("model_provider"))
                .where(field("workspace_id", UUID.class).eq(workspaceId)).orderBy(field("updated_at").desc())
                .fetch(this::mapProvider);
    }

    @Override
    @Transactional
    public ModelProvider createProvider(UUID workspaceId, String name, String providerType, String baseUrl, UUID secretReferenceId) {
        if (name == null || name.isBlank() || name.length() > 160) throw new IllegalArgumentException("Provider name is required.");
        if (!List.of("OPENAI_COMPATIBLE", "DETERMINISTIC_LOCAL").contains(providerType)) throw new IllegalArgumentException("Unsupported provider type.");
        if ("OPENAI_COMPATIBLE".equals(providerType) && secretReferenceId == null) throw new IllegalArgumentException("A Secret Reference is required for an OpenAI-compatible provider.");
        WorkspaceScope scope = workspaces.require(workspaceId);
        if (secretReferenceId != null) secrets.get(workspaceId, secretReferenceId);
        String normalizedUrl = endpoints.validate(baseUrl, providerType);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(table("model_provider"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("name"), field("provider_type"),
                        field("base_url"), field("secret_reference_id"), field("enabled"), field("version"), field("created_at"), field("updated_at"))
                .values(id, scope.tenantId(), workspaceId, name.trim(), providerType, normalizedUrl, secretReferenceId, true, 1L, now, now)
                .execute();
        return listProviders(workspaceId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Override
    public List<ModelDefinition> listModels(UUID workspaceId) {
        return sql.select().from(table("model_definition"))
                .where(field("workspace_id", UUID.class).eq(workspaceId)).orderBy(field("updated_at").desc())
                .fetch(this::mapModel);
    }

    @Override
    @Transactional
    public ModelDefinition createModel(UUID workspaceId, UUID providerId, String modelKey, String name,
            List<String> capabilities, long inputCost, long outputCost) {
        requireProvider(workspaceId, providerId);
        if (modelKey == null || modelKey.isBlank() || modelKey.length() > 200) throw new IllegalArgumentException("Model key is required.");
        if (name == null || name.isBlank() || name.length() > 160) throw new IllegalArgumentException("Model name is required.");
        if (inputCost < 0 || outputCost < 0) throw new IllegalArgumentException("Model costs must not be negative.");
        WorkspaceScope scope = workspaces.require(workspaceId);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(table("model_definition"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("provider_id"), field("model_key"),
                        field("name"), field("capabilities"), field("input_cost_micros_per_million"),
                        field("output_cost_micros_per_million"), field("enabled"), field("created_at"), field("updated_at"))
                .values(id, scope.tenantId(), workspaceId, providerId, modelKey.trim(), name.trim(),
                        JSONB.valueOf(json.writeValueAsString(capabilities == null ? List.of("CHAT") : capabilities)),
                        inputCost, outputCost, true, now, now)
                .execute();
        return listModels(workspaceId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Override
    public List<ModelRoute> listRoutes(UUID workspaceId) {
        return sql.select().from(table("model_route"))
                .where(field("workspace_id", UUID.class).eq(workspaceId)).orderBy(field("created_at").desc())
                .fetch(this::mapRoute);
    }

    @Override
    @Transactional
    public ModelRoute createRoute(UUID workspaceId, String name, UUID modelId, int timeoutMs, int maxOutputTokens, BigDecimal temperature) {
        requireModel(workspaceId, modelId);
        if (name == null || name.isBlank() || name.length() > 160) throw new IllegalArgumentException("Route name is required.");
        if (timeoutMs < 1000 || timeoutMs > 300000) throw new IllegalArgumentException("Route timeout must be between 1000 and 300000 ms.");
        if (maxOutputTokens < 1 || maxOutputTokens > 200000) throw new IllegalArgumentException("Maximum output tokens are out of range.");
        if (temperature != null && (temperature.compareTo(BigDecimal.ZERO) < 0 || temperature.compareTo(new BigDecimal("2")) > 0)) throw new IllegalArgumentException("Temperature must be between 0 and 2.");
        WorkspaceScope scope = workspaces.require(workspaceId);
        Long latest = sql.select(field("max(version)", Long.class)).from(table("model_route"))
                .where(field("workspace_id", UUID.class).eq(workspaceId).and(field("name", String.class).eq(name.trim())))
                .fetchOne(0, Long.class);
        long version = latest == null ? 1 : latest + 1;
        UUID id = UUID.randomUUID();
        sql.insertInto(table("model_route"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("name"), field("version"),
                        field("model_id"), field("status"), field("timeout_ms"), field("max_output_tokens"),
                        field("temperature"), field("created_at"))
                .values(id, scope.tenantId(), workspaceId, name.trim(), version, modelId, "PUBLISHED", timeoutMs,
                        maxOutputTokens, temperature, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
        return listRoutes(workspaceId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Override
    public List<PromptAsset> listPrompts(UUID workspaceId) {
        return sql.select().from(table("prompt_asset"))
                .where(field("workspace_id", UUID.class).eq(workspaceId)).orderBy(field("updated_at").desc())
                .fetch(this::mapPrompt);
    }

    @Override
    @Transactional
    public PromptAsset createPrompt(UUID workspaceId, String slug, String name, String description) {
        if (slug == null || !slug.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$") || slug.length() > 80) throw new IllegalArgumentException("Prompt slug is invalid.");
        if (name == null || name.isBlank() || name.length() > 160) throw new IllegalArgumentException("Prompt name is required.");
        WorkspaceScope scope = workspaces.require(workspaceId);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sql.insertInto(table("prompt_asset"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("slug"), field("name"),
                        field("description"), field("created_at"), field("updated_at"))
                .values(id, scope.tenantId(), workspaceId, slug, name.trim(), description == null ? "" : description, now, now)
                .execute();
        return listPrompts(workspaceId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Override
    public List<PromptVersion> listPromptVersions(UUID workspaceId, UUID promptAssetId) {
        requirePrompt(workspaceId, promptAssetId);
        return sql.select().from(table("prompt_version"))
                .where(field("workspace_id", UUID.class).eq(workspaceId)
                        .and(field("prompt_asset_id", UUID.class).eq(promptAssetId)))
                .orderBy(field("version").desc()).fetch(this::mapPromptVersion);
    }

    @Override
    @Transactional
    public PromptVersion createPromptVersion(UUID workspaceId, UUID promptAssetId, String systemPrompt, List<String> variables) {
        PromptAsset prompt = requirePrompt(workspaceId, promptAssetId);
        if (systemPrompt == null || systemPrompt.isBlank() || systemPrompt.length() > 50000) throw new IllegalArgumentException("System Prompt is required and must not exceed 50000 characters.");
        List<String> normalizedVariables = variables == null ? List.of() : variables.stream().distinct().sorted().toList();
        if (normalizedVariables.stream().anyMatch(value -> !value.matches("^[a-zA-Z][a-zA-Z0-9_]{0,63}$"))) throw new IllegalArgumentException("Prompt variable names are invalid.");
        Set<String> placeholders = new HashSet<>();
        Matcher matcher = PROMPT_VARIABLE.matcher(systemPrompt);
        while (matcher.find()) placeholders.add(matcher.group(1));
        if (!placeholders.equals(Set.copyOf(normalizedVariables))) {
            throw new IllegalArgumentException("Declared Prompt variables must exactly match template placeholders.");
        }
        Integer latest = sql.select(field("max(version)", Integer.class)).from(table("prompt_version"))
                .where(field("prompt_asset_id", UUID.class).eq(promptAssetId)).fetchOne(0, Integer.class);
        int version = latest == null ? 1 : latest + 1;
        UUID id = UUID.randomUUID();
        sql.insertInto(table("prompt_version"))
                .columns(field("id"), field("tenant_id"), field("workspace_id"), field("prompt_asset_id"),
                        field("version"), field("system_prompt"), field("variables"), field("status"), field("created_at"))
                .values(id, prompt.tenantId(), workspaceId, promptAssetId, version, systemPrompt,
                        JSONB.valueOf(json.writeValueAsString(normalizedVariables)), "PUBLISHED", OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
        return listPromptVersions(workspaceId, promptAssetId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Override
    public String modelRouteReference(UUID workspaceId, UUID modelRouteId) {
        ModelRoute route = listRoutes(workspaceId).stream().filter(item -> item.id().equals(modelRouteId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model route."));
        return route.reference();
    }

    @Override
    public String promptVersionReference(UUID workspaceId, UUID promptVersionId) {
        Record record = sql.select(
                        field("prompt_asset.slug", String.class).as("resolved_prompt_slug"),
                        field("prompt_version.version", Integer.class).as("resolved_prompt_version"))
                .from(table("prompt_version")).join(table("prompt_asset"))
                .on(field("prompt_version.prompt_asset_id", UUID.class).eq(field("prompt_asset.id", UUID.class)))
                .where(field("prompt_version.workspace_id", UUID.class).eq(workspaceId)
                        .and(field("prompt_version.id", UUID.class).eq(promptVersionId)))
                .fetchOptional().orElseThrow(() -> new IllegalArgumentException("Unknown Prompt version."));
        return record.get("resolved_prompt_slug", String.class) + "@" + record.get("resolved_prompt_version", Integer.class);
    }

    @Override
    public RuntimeConfiguration resolve(UUID workspaceId, String routeReference, String promptReference) {
        Reference routeRef = Reference.parse(routeReference, "model route");
        Reference promptRef = Reference.parse(promptReference, "Prompt");
        Record route = sql.select(
                        field("model_provider.id", UUID.class).as("resolved_provider_id"),
                        field("model_provider.provider_type", String.class).as("resolved_provider_type"),
                        field("model_provider.base_url", String.class).as("resolved_base_url"),
                        field("model_provider.secret_reference_id", UUID.class).as("resolved_secret_id"),
                        field("model_definition.model_key", String.class).as("resolved_model_key"),
                        field("model_definition.capabilities", JSONB.class).as("resolved_capabilities"),
                        field("model_definition.input_cost_micros_per_million", Long.class).as("resolved_input_cost"),
                        field("model_definition.output_cost_micros_per_million", Long.class).as("resolved_output_cost"),
                        field("model_route.timeout_ms", Integer.class).as("resolved_timeout"),
                        field("model_route.max_output_tokens", Integer.class).as("resolved_max_output"),
                        field("model_route.temperature", BigDecimal.class).as("resolved_temperature"))
                .from(table("model_route")).join(table("model_definition"))
                .on(field("model_route.model_id", UUID.class).eq(field("model_definition.id", UUID.class)))
                .join(table("model_provider"))
                .on(field("model_definition.provider_id", UUID.class).eq(field("model_provider.id", UUID.class)))
                .where(field("model_route.workspace_id", UUID.class).eq(workspaceId)
                        .and(field("model_route.name", String.class).eq(routeRef.name()))
                        .and(field("model_route.version", Long.class).eq(routeRef.version())))
                .fetchOptional().orElseThrow(() -> new IllegalArgumentException("Unknown published model route version."));
        Record prompt = sql.select(field("prompt_version.system_prompt", String.class).as("resolved_system_prompt"))
                .from(table("prompt_version")).join(table("prompt_asset"))
                .on(field("prompt_version.prompt_asset_id", UUID.class).eq(field("prompt_asset.id", UUID.class)))
                .where(field("prompt_version.workspace_id", UUID.class).eq(workspaceId)
                        .and(field("prompt_asset.slug", String.class).eq(promptRef.name()))
                        .and(field("prompt_version.version", Integer.class).eq(Math.toIntExact(promptRef.version()))))
                .fetchOptional().orElseThrow(() -> new IllegalArgumentException("Unknown published Prompt version."));
        UUID secretId = route.get("resolved_secret_id", UUID.class);
        char[] apiKey = new char[0];
        if (secretId != null) {
            try (ResolvedSecret resolved = secrets.resolve(workspaceId, secretId)) {
                apiKey = resolved.value().clone();
            }
        }
        boolean reasoning = false;
        try {
            JsonNode capabilitiesNode = json.readTree(route.get("resolved_capabilities", JSONB.class).data());
            reasoning = capabilitiesNode.valueStream().anyMatch(node -> node.isString() && "REASONING".equals(node.stringValue()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored model capabilities are invalid.", exception);
        }
        String providerType = route.get("resolved_provider_type", String.class);
        String checkedBaseUrl = endpoints.validate(route.get("resolved_base_url", String.class), providerType);
        return new RuntimeConfiguration(
                providerType, route.get("resolved_provider_id", UUID.class).toString(),
                checkedBaseUrl, route.get("resolved_model_key", String.class), apiKey,
                prompt.get("resolved_system_prompt", String.class), route.get("resolved_timeout", Integer.class),
                route.get("resolved_max_output", Integer.class), route.get("resolved_temperature", BigDecimal.class),
                route.get("resolved_input_cost", Long.class), route.get("resolved_output_cost", Long.class), reasoning);
    }

    private ModelProvider requireProvider(UUID workspaceId, UUID id) {
        return listProviders(workspaceId).stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model provider."));
    }

    private ModelDefinition requireModel(UUID workspaceId, UUID id) {
        return listModels(workspaceId).stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model."));
    }

    private PromptAsset requirePrompt(UUID workspaceId, UUID id) {
        return listPrompts(workspaceId).stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Prompt."));
    }

    private ModelProvider mapProvider(Record record) {
        return new ModelProvider(record.get("id", UUID.class), record.get("tenant_id", UUID.class),
                record.get("workspace_id", UUID.class), record.get("name", String.class),
                record.get("provider_type", String.class), record.get("base_url", String.class),
                record.get("secret_reference_id", UUID.class), Boolean.TRUE.equals(record.get("enabled", Boolean.class)),
                record.get("version", Long.class), record.get("created_at", OffsetDateTime.class), record.get("updated_at", OffsetDateTime.class));
    }

    private ModelDefinition mapModel(Record record) {
        try {
            List<String> capabilities = json.readerForListOf(String.class).readValue(record.get("capabilities", JSONB.class).data());
            return new ModelDefinition(record.get("id", UUID.class), record.get("tenant_id", UUID.class),
                    record.get("workspace_id", UUID.class), record.get("provider_id", UUID.class), record.get("model_key", String.class),
                    record.get("name", String.class), capabilities, record.get("input_cost_micros_per_million", Long.class),
                    record.get("output_cost_micros_per_million", Long.class), Boolean.TRUE.equals(record.get("enabled", Boolean.class)),
                    record.get("created_at", OffsetDateTime.class), record.get("updated_at", OffsetDateTime.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored model capabilities are invalid.", exception);
        }
    }

    private ModelRoute mapRoute(Record record) {
        return new ModelRoute(record.get("id", UUID.class), record.get("tenant_id", UUID.class),
                record.get("workspace_id", UUID.class), record.get("name", String.class), record.get("version", Long.class),
                record.get("model_id", UUID.class), record.get("status", String.class), record.get("timeout_ms", Integer.class),
                record.get("max_output_tokens", Integer.class), record.get("temperature", BigDecimal.class), record.get("created_at", OffsetDateTime.class));
    }

    private PromptAsset mapPrompt(Record record) {
        return new PromptAsset(record.get("id", UUID.class), record.get("tenant_id", UUID.class),
                record.get("workspace_id", UUID.class), record.get("slug", String.class), record.get("name", String.class),
                record.get("description", String.class), record.get("created_at", OffsetDateTime.class), record.get("updated_at", OffsetDateTime.class));
    }

    private PromptVersion mapPromptVersion(Record record) {
        try {
            List<String> variables = json.readerForListOf(String.class).readValue(record.get("variables", JSONB.class).data());
            return new PromptVersion(record.get("id", UUID.class), record.get("tenant_id", UUID.class),
                    record.get("workspace_id", UUID.class), record.get("prompt_asset_id", UUID.class),
                    record.get("version", Integer.class), record.get("system_prompt", String.class), variables,
                    record.get("status", String.class), record.get("created_at", OffsetDateTime.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored Prompt variables are invalid.", exception);
        }
    }

    private record Reference(String name, long version) {
        static Reference parse(String value, String label) {
            if (value == null) throw new IllegalArgumentException("Pinned " + label + " reference is required.");
            int marker = value.lastIndexOf('@');
            if (marker < 1 || marker == value.length() - 1) throw new IllegalArgumentException("Pinned " + label + " reference is invalid.");
            try {
                return new Reference(value.substring(0, marker), Long.parseLong(value.substring(marker + 1)));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Pinned " + label + " version must be numeric.");
            }
        }
    }
}
