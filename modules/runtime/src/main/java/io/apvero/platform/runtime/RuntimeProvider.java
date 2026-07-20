package io.apvero.platform.runtime;

import io.apvero.platform.release.ReleaseBundle;

public interface RuntimeProvider {
    String id();

    boolean supports(ReleaseBundle release);

    ProviderResult execute(ProviderRequest request);
}
