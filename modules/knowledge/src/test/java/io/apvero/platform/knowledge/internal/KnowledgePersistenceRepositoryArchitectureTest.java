package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.apvero.platform.identity.WorkspaceScope;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class KnowledgePersistenceRepositoryArchitectureTest {

    @Test
    void everyRepositoryOperationRequiresFullWorkspaceScopeAsItsFirstArgument() {
        for (Method method : KnowledgePersistenceRepository.class.getDeclaredMethods()) {
            assertThat(method.getParameterTypes())
                    .as("%s must be scoped before any resource identifier", method.getName())
                    .isNotEmpty()
                    .startsWith(WorkspaceScope.class);
        }
    }
}
