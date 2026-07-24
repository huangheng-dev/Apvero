package io.apvero.platform.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.apvero.platform.identity.WorkspaceScope;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class ExecutionComponentPersistenceRepositoryArchitectureTest {
    @Test
    void everyComponentPersistenceOperationRequiresFullWorkspaceScopeFirst() {
        for (Method method : ExecutionComponentPersistenceRepository.class.getDeclaredMethods()) {
            assertThat(method.getParameterTypes())
                    .as("%s must fail closed by full workspace scope", method.getName())
                    .isNotEmpty()
                    .startsWith(WorkspaceScope.class);
        }
    }
}
