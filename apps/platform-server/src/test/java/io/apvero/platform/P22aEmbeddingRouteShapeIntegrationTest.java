package io.apvero.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
class P22aEmbeddingRouteShapeIntegrationTest {
    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROVIDER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000003001");

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_test")
            .withUsername("apvero")
            .withPassword("apvero");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Autowired
    JdbcTemplate sql;

    @Test
    void backfillsChatAndEnforcesEmbeddingShapeCapabilityAndImmutability() {
        assertThat(sql.queryForObject("""
                select route_capability
                from model_route
                where id = '00000000-0000-0000-0000-000000003201'::uuid
                """, String.class)).isEqualTo("CHAT");
        assertThat(sql.queryForObject("""
                select count(*)
                from model_route
                where route_capability = 'CHAT'
                  and embedding_dimension is null
                  and embedding_maximum_input_tokens is null
                  and embedding_maximum_batch_size is null
                  and embedding_normalization is null
                """, Integer.class)).isGreaterThan(0);

        UUID embeddingModelId = createModel("embedding-model", "[\"EMBEDDING\"]");
        UUID embeddingRouteId = UUID.randomUUID();
        assertThat(sql.update("""
                insert into model_route(
                    id, tenant_id, workspace_id, name, version, model_id, route_capability,
                    status, timeout_ms, max_output_tokens, temperature, embedding_dimension,
                    embedding_maximum_input_tokens, embedding_maximum_batch_size,
                    embedding_normalization, created_at)
                values (?, ?, ?, 'quick-start-embedding', 1, ?, 'EMBEDDING',
                    'PUBLISHED', 30000, null, null, 256, 8192, 64, 'L2', now())
                """, embeddingRouteId, TENANT_ID, WORKSPACE_ID, embeddingModelId)).isEqualTo(1);

        assertThatThrownBy(() -> sql.update("""
                insert into model_route(
                    id, tenant_id, workspace_id, name, version, model_id, route_capability,
                    status, timeout_ms, max_output_tokens, temperature, embedding_dimension,
                    embedding_maximum_input_tokens, embedding_maximum_batch_size,
                    embedding_normalization, created_at)
                values (?, ?, ?, 'invalid-dimension', 1, ?, 'EMBEDDING',
                    'PUBLISHED', 30000, null, null, 16001, 8192, 64, 'L2', now())
                """, UUID.randomUUID(), TENANT_ID, WORKSPACE_ID, embeddingModelId))
                .isInstanceOf(DataAccessException.class);

        UUID chatModelId = createModel("chat-only-model", "[\"CHAT\"]");
        assertThatThrownBy(() -> sql.update("""
                insert into model_route(
                    id, tenant_id, workspace_id, name, version, model_id, route_capability,
                    status, timeout_ms, max_output_tokens, temperature, embedding_dimension,
                    embedding_maximum_input_tokens, embedding_maximum_batch_size,
                    embedding_normalization, created_at)
                values (?, ?, ?, 'wrong-model-capability', 1, ?, 'EMBEDDING',
                    'PUBLISHED', 30000, null, null, 256, 8192, 64, 'L2', now())
                """, UUID.randomUUID(), TENANT_ID, WORKSPACE_ID, chatModelId))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> sql.update(
                "update model_route set embedding_dimension = 384 where id = ?", embeddingRouteId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update(
                "delete from model_route where id = ?", embeddingRouteId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void upgradesARealV8SchemaToV9WithoutRewritingChatRoutes() throws Exception {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway toV8 = Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("8"))
                    .load();
            assertThat(toV8.migrate().migrationsExecuted).isEqualTo(8);
            assertThat(toV8.info().current().getVersion().getVersion()).isEqualTo("8");

            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                connection.setSchema(schema);
                try (var statement = connection.prepareStatement("""
                        update model_definition
                        set capabilities = '["REASONING"]'::jsonb
                        where id = '00000000-0000-0000-0000-000000003101'::uuid
                        """)) {
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
            }

            Flyway toV9 = Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("9"))
                    .load();
            assertThat(toV9.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(toV9.info().current().getVersion().getVersion()).isEqualTo("9");

            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                connection.setSchema(schema);
                try (var statement = connection.prepareStatement("""
                        select route.route_capability, route.max_output_tokens,
                            route.embedding_dimension, model.capabilities
                        from model_route route
                        join model_definition model on model.id = route.model_id
                        where route.id = '00000000-0000-0000-0000-000000003201'::uuid
                        """);
                        var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("route_capability")).isEqualTo("CHAT");
                    assertThat(result.getInt("max_output_tokens")).isEqualTo(512);
                    assertThat(result.getObject("embedding_dimension")).isNull();
                    assertThat(result.getString("capabilities")).contains("REASONING", "CHAT");
                }
            }
        } finally {
            sql.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private UUID createModel(String modelKey, String capabilities) {
        UUID id = UUID.randomUUID();
        sql.update("""
                insert into model_definition(
                    id, tenant_id, workspace_id, provider_id, model_key, name, capabilities,
                    input_cost_micros_per_million, output_cost_micros_per_million,
                    enabled, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, 0, 0, true, now(), now())
                """, id, TENANT_ID, WORKSPACE_ID, PROVIDER_ID, modelKey, modelKey, capabilities);
        return id;
    }
}
