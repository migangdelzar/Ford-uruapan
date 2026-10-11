package com.ford.service.ui.adapter.in.web;

import com.ford.service.ui.application.UiProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Controller
public class UiOriginProxyController {
    private static final String REVALIDATE_CACHE_CONTROL = "public, max-age=0, must-revalidate";
    private static final String IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";
    private static final Pattern FINGERPRINTED_ASSET =
            Pattern.compile("^/assets/.+-[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9]+$");
    private static final Set<String> RESERVED_ROOTS = Set.of(
            "api", "oauth2", ".well-known", "logout", "webauthn", "actuator", "webhooks", "error");
    private static final Set<String> REQUEST_HEADERS = Set.of(
            "accept", "accept-language", "if-none-match", "if-modified-since", "range", "if-range");
    private static final Set<String> RESPONSE_HEADERS = Set.of(
            "content-type", "content-length", "content-encoding", "cache-control", "etag", "last-modified",
            "expires", "vary", "content-range", "accept-ranges", "location", "content-security-policy",
            "content-security-policy-report-only", "permissions-policy", "referrer-policy", "strict-transport-security",
            "x-content-type-options", "x-frame-options", "cross-origin-embedder-policy",
            "cross-origin-opener-policy", "cross-origin-resource-policy", "content-disposition");

    private final RestClient restClient;
    private final UiProxyProperties properties;

    public UiOriginProxyController(@Qualifier("uiProxyRestClient") RestClient restClient, UiProxyProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @org.springframework.web.bind.annotation.RequestMapping(path = {"/", "/{*path}"}, method = {org.springframework.web.bind.annotation.RequestMethod.GET, org.springframework.web.bind.annotation.RequestMethod.HEAD})
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        if (isReserved(path)) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        var upstreamUri = upstreamUri(request);
        var method = "HEAD".equalsIgnoreCase(request.getMethod()) ? HttpMethod.HEAD : HttpMethod.GET;
        try {
            restClient.method(method).uri(upstreamUri)
                    .headers(headers -> copyRequestHeaders(request, headers))
                    .exchange((clientRequest, clientResponse) -> {
                        response.setStatus(clientResponse.getStatusCode().value());
                        copyResponseHeaders(clientResponse.getHeaders(), response);
                        response.setHeader("Cache-Control", cacheControlFor(path));
                        if (method != HttpMethod.HEAD && clientResponse.getBody() != null) {
                            clientResponse.getBody().transferTo(response.getOutputStream());
                        }
                        return null;
                    });
        } catch (RestClientException exception) {
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(HttpStatus.BAD_GATEWAY.value());
            }
        }
    }

    private URI upstreamUri(HttpServletRequest request) {
        var builder = UriComponentsBuilder.fromUri(properties.upstreamUrl())
                .path(request.getRequestURI());
        if (request.getQueryString() != null) builder.query(request.getQueryString());
        return builder.build(true).toUri();
    }

    private static String cacheControlFor(String path) {
        return FINGERPRINTED_ASSET.matcher(path).matches()
                ? IMMUTABLE_CACHE_CONTROL
                : REVALIDATE_CACHE_CONTROL;
    }

    private static boolean isReserved(String path) {
        if (path.equals("/login/ott") || path.startsWith("/login/ott/") ||
                path.equals("/login/one-time-token") || path.startsWith("/login/one-time-token/")) return true;
        var first = path.substring(1).split("/", 2)[0].toLowerCase(Locale.ROOT);
        return RESERVED_ROOTS.contains(first);
    }

    private static void copyRequestHeaders(HttpServletRequest request, HttpHeaders target) {
        var names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            var name = names.nextElement();
            if (!REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) continue;
            var values = request.getHeaders(name);
            while (values.hasMoreElements()) target.add(name, values.nextElement());
        }
    }

    private void copyResponseHeaders(HttpHeaders source, HttpServletResponse target) {
        source.forEach((name, values) -> {
            if (!RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) return;
            for (var value : values) {
                target.addHeader(name, "location".equalsIgnoreCase(name) ? rewriteLocation(value) : value);
            }
        });
    }

    private String rewriteLocation(String value) {
        try {
            var location = URI.create(value);
            if (location.isAbsolute() && properties.upstreamUrl().getHost().equalsIgnoreCase(location.getHost())) {
                return properties.publicOrigin().resolve(location.getRawPath() +
                        (location.getRawQuery() == null ? "" : "?" + location.getRawQuery()) +
                        (location.getRawFragment() == null ? "" : "#" + location.getRawFragment())).toString();
            }
        } catch (IllegalArgumentException ignored) {
            // Preserve malformed or relative upstream Location values as received.
        }
        return value;
    }
}
