package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.identity.WorkspaceScope;
import io.apvero.platform.identity.WorkspaceScopeCatalog;
import io.apvero.platform.knowledge.KnowledgeAvailability;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.internal.KnowledgeIndexPersistenceRecords.VersionRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultKnowledgeIndexVersionCatalogTest {
    private final KnowledgeAvailability availability = mock(KnowledgeAvailability.class);
    private final WorkspaceScopeCatalog workspaces = mock(WorkspaceScopeCatalog.class);
    private final KnowledgeIndexPersistenceRepository versions =
            mock(KnowledgeIndexPersistenceRepository.class);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final WorkspaceScope scope = new WorkspaceScope(tenantId, workspaceId);
    private final DefaultKnowledgeIndexVersionCatalog catalog =
            new DefaultKnowledgeIndexVersionCatalog(availability, workspaces, versions);

    @BeforeEach
    void setUp() {
        when(workspaces.require(workspaceId)).thenReturn(scope);
    }

    @Test
    void listsAllReadyVersionsWithinTheRequiredWorkspaceScope() {
        VersionRow row = version();
        when(versions.listVersions(scope)).thenReturn(List.of(row));

        assertThat(catalog.list(workspaceId, null))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.id()).isEqualTo(row.id());
                    assertThat(result.reference()).isEqualTo(row.reference());
                    assertThat(result.sourceRevisionCount()).isEqualTo(row.sourceCount());
                });
        verify(availability).requireEnabled();
        verify(versions).listVersions(scope);
    }

    @Test
    void filtersByOpaqueIndexIdWithoutCrossModuleResolution() {
        UUID indexId = UUID.randomUUID();
        when(versions.listVersions(scope, indexId)).thenReturn(List.of());

        assertThat(catalog.list(workspaceId, indexId)).isEmpty();
        verify(versions).listVersions(scope, indexId);
    }

    @Test
    void resolvesOneExactVersionAndFailsClosedWhenItIsOutsideTheScope() {
        VersionRow row = version();
        when(versions.findVersion(scope, row.id())).thenReturn(Optional.of(row));
        assertThat(catalog.get(workspaceId, row.id()).id()).isEqualTo(row.id());

        UUID unknown = UUID.randomUUID();
        when(versions.findVersion(scope, unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> catalog.get(workspaceId, unknown))
                .isInstanceOf(KnowledgeException.class)
                .hasMessage("APVERO_KNOWLEDGE_INDEX_VERSION_NOT_FOUND");
    }

    private VersionRow version() {
        return new VersionRow(
                UUID.randomUUID(),
                tenantId,
                workspaceId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "1.0.0",
                "support@1.0.0",
                UUID.randomUUID(),
                "embedding@1",
                3,
                1,
                2,
                "sha256:" + "a".repeat(64),
                "READY",
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
