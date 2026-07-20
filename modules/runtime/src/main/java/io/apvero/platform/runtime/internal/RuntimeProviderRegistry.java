package io.apvero.platform.runtime.internal;

import io.apvero.platform.release.ReleaseBundle;
import io.apvero.platform.runtime.RuntimeProvider;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class RuntimeProviderRegistry {
    private final List<RuntimeProvider> providers;

    RuntimeProviderRegistry(List<RuntimeProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    RuntimeProvider resolve(ReleaseBundle release) {
        return providers.stream()
                .filter(provider -> provider.supports(release))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No runtime provider supports model route "
                                + release.manifest().path("modelRouteVersion").stringValue("<missing>")));
    }
}
