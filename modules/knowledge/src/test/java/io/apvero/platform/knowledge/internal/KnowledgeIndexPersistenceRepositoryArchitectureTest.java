package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.apvero.platform.identity.WorkspaceScope;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class KnowledgeIndexPersistenceRepositoryArchitectureTest {
    @Test
    void everyIndexPersistenceOperationRequiresFullWorkspaceScopeFirst() {
        for (Method method : KnowledgeIndexPersistenceRepository.class.getDeclaredMethods()) {
            assertThat(method.getParameterTypes())
                    .as("%s must fail closed by full workspace scope", method.getName())
                    .isNotEmpty()
                    .startsWith(WorkspaceScope.class);
        }
    }
}
