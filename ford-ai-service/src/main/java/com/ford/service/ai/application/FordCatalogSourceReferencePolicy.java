package com.ford.service.ai.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/** Allows safe, canonical URLs for public Ford catalog sources. */
public final class FordCatalogSourceReferencePolicy {

    public Optional<String> safeReference(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return Optional.empty();
        try {
            var uri = URI.create(rawUrl.strip());
            var host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !isFordHost(host)) {
                return Optional.empty();
            }
            var canonicalHost = host.toLowerCase(Locale.ROOT);
            return Optional.of(new URI("https", null, canonicalHost, -1,
                    uri.getPath(), null, null).toASCIIString());
        } catch (IllegalArgumentException | URISyntaxException exception) {
            return Optional.empty();
        }
    }

    private static boolean isFordHost(String host) {
        var normalized = host.toLowerCase(Locale.ROOT);
        return isHostOrSubdomain(normalized, "forduruapan.mx") || isHostOrSubdomain(normalized, "ford.mx");
    }

    private static boolean isHostOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
