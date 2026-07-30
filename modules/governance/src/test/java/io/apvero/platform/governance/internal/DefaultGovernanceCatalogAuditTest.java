package io.apvero.platform.governance.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.apvero.platform.identity.WorkspaceScopeCatalog;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DefaultGovernanceCatalogAuditTest {
    @Test
    void rejectsUnsafeDigestBeforeResolvingWorkspaceOrWritingAudit() {
        DefaultGovernanceCatalog catalog = new DefaultGovernanceCatalog(
                mock(DSLContext.class),
                mock(WorkspaceScopeCatalog.class),
                mock(ObjectMapper.class),
                mock(PolicyDecisionAudit.class),
                mock(ExecutionComponentPersistenceRepository.class));

        assertThatThrownBy(() -> catalog.appendWithDigest(
                        java.util.UUID.randomUUID(),
                        "actor",
                        "action",
                        "resource",
                        "id",
                        "SUCCEEDED",
                        null,
                        "trace",
                        "not-a-digest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APVERO_AUDIT_DIGEST_INVALID");
    }
}
