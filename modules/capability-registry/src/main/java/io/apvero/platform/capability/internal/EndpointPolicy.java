package io.apvero.platform.capability.internal;

import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class EndpointPolicy {
    private final boolean allowPrivate;

    EndpointPolicy(@Value("${apvero.providers.allow-private-endpoints:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    String validate(String value, String providerType) {
        if ("DETERMINISTIC_LOCAL".equals(providerType)) return "local://deterministic";
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Provider Base URL is invalid.");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getHost() == null) {
            throw new IllegalArgumentException("Provider Base URL must not contain credentials, query, or fragment.");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            assertPublicDestination(uri.getHost());
            return uri.toString().replaceAll("/+$", "");
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && allowPrivate) return uri.toString().replaceAll("/+$", "");
        throw new IllegalArgumentException("Provider Base URL must use HTTPS unless private endpoints are explicitly enabled.");
    }

    private void assertPublicDestination(String host) {
        if (allowPrivate) return;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("Provider Base URL resolves to a private or local destination.");
                }
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Provider Base URL host cannot be resolved.");
        }
    }
}
