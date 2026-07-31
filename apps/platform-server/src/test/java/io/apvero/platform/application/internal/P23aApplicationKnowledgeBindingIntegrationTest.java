package io.apvero.platform.application.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.application.ApplicationCatalog;
import io.apvero.platform.application.ApplicationKnowledgeBindingException;
import io.apvero.platform.application.ApplicationNotFoundException;
import io.apvero.platform.application.CreateApplicationCommand;
import io.apvero.platform.application.ReplaceApplicationKnowledgeBindingsCommand;
import io.apvero.platform.application.RuntimeMode;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
class P23aApplicationKnowledgeBindingIntegrationTest {
    static final UUID WORKSPACE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("apvero_p23a_test")
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

    @Autowired ApplicationCatalog applications;
    @Autowired JdbcTemplate sql;

    @Test
    void cleanMigrationCreatesOneApplicationOwnedOpaqueBindingTable() {
        assertThat(sql.queryForObject(
                "select count(*) from flyway_schema_history where version = '12' and success",
                Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = current_schema()
                  and table_name = 'application_draft_knowledge_binding'
                """, Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select count(*)
                from information_schema.table_constraints
                where table_schema = current_schema()
                  and table_name = 'application_draft_knowledge_binding'
                  and constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select count(distinct trigger_name)
                from information_schema.triggers
                where trigger_schema = current_schema()
                  and trigger_name in (
                      'application_draft_knowledge_binding_validates_mode',
                      'application_runtime_mode_preserves_knowledge_binding')
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void upgradesTheRealV11SchemaToV12WithoutRewritingExistingApplications() {
        String schema = "upgrade_v12_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway toV11 = flyway(schema, "11");
            assertThat(toV11.migrate().migrationsExecuted).isEqualTo(11);
            int applicationCount = count(schema, "ai_application");

            Flyway toV12 = flyway(schema, "12");
            assertThat(toV12.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(count(schema, "ai_application")).isEqualTo(applicationCount);
            assertThat(count(schema, "application_draft_knowledge_binding")).isZero();
        } finally {
            sql.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void persistsOpaqueIdsInOrderAndRejectsAStaleReplaceWithoutPartialMutation() {
        var application = applications.create(
                WORKSPACE_ID,
                new CreateApplicationCommand(
                        slug("rag"),
                        "RAG binding integration",
                        "",
                        RuntimeMode.RAG));
        var first = selection();
        var second = selection();

        var replaced = applications.replaceDraftKnowledgeBindings(
                WORKSPACE_ID,
                application.id(),
                new ReplaceApplicationKnowledgeBindingsCommand(
                        application.version(),
                        List.of(first, second)));

        assertThat(replaced.applicationVersion()).isEqualTo(application.version() + 1);
        assertThat(replaced.bindings())
                .extracting(
                        binding -> binding.indexVersionId(),
                        binding -> binding.retrievalPolicyVersionId(),
                        binding -> binding.bindingOrder())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                first.indexVersionId(), first.retrievalPolicyVersionId(), 0),
                        org.assertj.core.groups.Tuple.tuple(
                                second.indexVersionId(), second.retrievalPolicyVersionId(), 1));

        assertThatThrownBy(() -> applications.replaceDraftKnowledgeBindings(
                        WORKSPACE_ID,
                        application.id(),
                        new ReplaceApplicationKnowledgeBindingsCommand(
                                application.version(), List.of(selection()))))
                .isInstanceOf(ApplicationKnowledgeBindingException.class)
                .hasMessage("APVERO_APPLICATION_DRAFT_VERSION_CONFLICT");
        assertThat(applications.getDraftKnowledgeBindings(WORKSPACE_ID, application.id()))
                .isEqualTo(replaced);
        assertThatThrownBy(() -> sql.update(
                        "update ai_application set runtime_mode = 'CHAT' where id = ?",
                        application.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("must remain in RAG runtime mode");
    }

    @Test
    void failsClosedAcrossWorkspacesAndAtTheDatabaseModeBoundary() {
        var rag = applications.create(
                WORKSPACE_ID,
                new CreateApplicationCommand(
                        slug("scope"),
                        "Scoped RAG",
                        "",
                        RuntimeMode.RAG));
        UUID otherTenantId = UUID.randomUUID();
        UUID otherWorkspaceId = UUID.randomUUID();
        sql.update(
                "insert into tenant(id, slug, name, created_at) values (?, ?, 'Other', now())",
                otherTenantId, slug("tenant"));
        sql.update(
                "insert into workspace(id, tenant_id, slug, name, created_at) values (?, ?, ?, 'Other', now())",
                otherWorkspaceId, otherTenantId, slug("workspace"));

        assertThatThrownBy(() -> applications.getDraftKnowledgeBindings(
                        otherWorkspaceId, rag.id()))
                .isInstanceOf(ApplicationNotFoundException.class);
        assertThatThrownBy(() -> sql.update("""
                        insert into application_draft_knowledge_binding(
                            application_id, tenant_id, workspace_id, binding_order,
                            knowledge_index_version_id, retrieval_policy_version_id,
                            created_at, updated_at)
                        values (?, ?, ?, 0, ?, ?, now(), now())
                        """, rag.id(), otherTenantId, otherWorkspaceId,
                        UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);

        var chat = applications.create(
                WORKSPACE_ID,
                new CreateApplicationCommand(
                        slug("chat"),
                        "CHAT binding guard",
                        "",
                        RuntimeMode.CHAT));
        assertThatThrownBy(() -> sql.update("""
                        insert into application_draft_knowledge_binding(
                            application_id, tenant_id, workspace_id, binding_order,
                            knowledge_index_version_id, retrieval_policy_version_id,
                            created_at, updated_at)
                        select id, tenant_id, workspace_id, 0, ?, ?, now(), now()
                        from ai_application where id = ?
                        """, UUID.randomUUID(), UUID.randomUUID(), chat.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("require RAG runtime mode");
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private int count(String schema, String table) {
        return sql.queryForObject("""
                select count(*)
                from %s.%s
                """.formatted(schema, table), Integer.class);
    }

    private static ReplaceApplicationKnowledgeBindingsCommand.BindingSelection selection() {
        return new ReplaceApplicationKnowledgeBindingsCommand.BindingSelection(
                UUID.randomUUID(), UUID.randomUUID());
    }

    private static String slug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
