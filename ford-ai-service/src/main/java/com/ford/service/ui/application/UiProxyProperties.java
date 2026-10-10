package com.ford.service.ui.application;

import java.net.URI;

public record UiProxyProperties(URI upstreamUrl, URI publicOrigin) {
    public UiProxyProperties {
        requireOrigin(upstreamUrl, "ford.web.ui.upstream-url", true);
        requireOrigin(publicOrigin, "ford.web.ui.public-origin", true);
    }

    private static void requireOrigin(URI uri, String propertyName, boolean allowLoopbackHttp) {
        if (uri == null) throw new IllegalArgumentException(propertyName + " is required");
        var https = uri != null && "https".equalsIgnoreCase(uri.getScheme());
        var loopbackHttp = allowLoopbackHttp && uri != null && "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if (!https && !loopbackHttp) throw new IllegalArgumentException(propertyName + " must use HTTPS");
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException(propertyName + " must be an origin without credentials, path, query, or fragment");
        }
    }
}
