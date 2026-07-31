package io.apvero.platform.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.apvero.platform.release.ReleaseCatalog;
import io.apvero.platform.runtime.RunCatalog;
import io.apvero.platform.runtime.RunCitationCatalog;
import io.apvero.platform.runtime.RunRetrievalEvidence;
import io.apvero.platform.runtime.RunRetrievalEvidenceCatalog;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunRetrievalEvidenceControllerTest {
    @Test
    void mapsTheWorkspaceScopedReadContractWithoutAcceptingTenantInput() {
        RunCatalog runs = mock(RunCatalog.class);
        ReleaseCatalog releases = mock(ReleaseCatalog.class);
        RunRetrievalEvidenceCatalog evidence = mock(RunRetrievalEvidenceCatalog.class);
        RunCitationCatalog citations = mock(RunCitationCatalog.class);
        RunController controller = new RunController(runs, releases, evidence, citations);
        UUID workspaceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        var expected = new RunRetrievalEvidence(runId, List.of());
        when(evidence.get(workspaceId, runId)).thenReturn(expected);

        assertThat(controller.retrieval(workspaceId, runId)).isSameAs(expected);
        verify(evidence).get(workspaceId, runId);

        when(citations.list(workspaceId, runId)).thenReturn(List.of());
        assertThat(controller.citations(workspaceId, runId)).isEmpty();
        verify(citations).list(workspaceId, runId);
    }
}
