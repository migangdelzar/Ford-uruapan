package com.ford.service.ai.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps customer-facing citations limited to source URLs returned by Ford's read-only tools. */
public final class GroundedSourceLinkRenderer {

    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\\\"'<>),;\\]}]+");

    public String render(String answer, Collection<String> toolReturnedUrls) {
        var trusted = new LinkedHashSet<String>();
        if (toolReturnedUrls != null) {
            toolReturnedUrls.stream().map(GroundedSourceLinkRenderer::canonicalFordUrl)
                    .flatMap(Optional::stream).forEach(trusted::add);
        }
        var safeAnswer = sanitizeAnswer(answer == null ? "" : answer, trusted);
        if (trusted.isEmpty()) return safeAnswer;
        var sources = trusted.stream().map(url -> "[" + label(url) + "](" + url + ")")
                .collect(java.util.stream.Collectors.joining(" · "));
        return safeAnswer.stripTrailing() + "\n\nFuentes: " + sources;
    }

    /** Removes tracking queries and fragments from HTTP URLs before tool results reach the model. */
    public String sanitizeToolOutput(String result) {
        if (result == null || result.isEmpty()) return result;
        var matcher = URL.matcher(result);
        var sanitized = new StringBuffer();
        while (matcher.find()) {
            var token = matcher.group();
            var candidate = token;
            while (!candidate.isEmpty() && ".!?".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            var replacement = canonicalHttpUrl(candidate).orElse(candidate)
                    + token.substring(candidate.length());
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    public Set<String> trustedFordUrls(Collection<String> urls) {
        var result = new LinkedHashSet<String>();
        if (urls != null) {
            urls.stream().map(GroundedSourceLinkRenderer::canonicalFordUrl)
                    .flatMap(Optional::stream).forEach(result::add);
        }
        return Set.copyOf(result);
    }

    public static Optional<String> canonicalFordUrl(String value) {
        return canonicalHttpUrl(value).filter(url -> {
            try {
                var host = URI.create(url).getHost();
                return "forduruapan.mx".equalsIgnoreCase(host)
                        || "www.forduruapan.mx".equalsIgnoreCase(host);
            }
            catch (IllegalArgumentException exception) {
                return false;
            }
        }).filter(url -> url.startsWith("https://"));
    }

    private static Optional<String> canonicalHttpUrl(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            var parsed = new URI(value);
            var scheme = parsed.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || parsed.getHost() == null) return Optional.empty();
            var clean = new URI(scheme.toLowerCase(Locale.ROOT), null, parsed.getHost().toLowerCase(Locale.ROOT),
                    parsed.getPort(), parsed.getPath(), null, null);
            return Optional.of(clean.toASCIIString());
        }
        catch (URISyntaxException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String sanitizeAnswer(String answer, Set<String> trusted) {
        var markdown = MARKDOWN_LINK.matcher(answer);
        var result = new StringBuffer();
        while (markdown.find()) {
            var link = canonicalFordUrl(markdown.group(2));
            var replacement = link.filter(trusted::contains)
                    .map(url -> "[" + markdown.group(1) + "](" + url + ")")
                    .orElse(markdown.group(1));
            markdown.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        markdown.appendTail(result);

        var urls = URL.matcher(result.toString());
        var withoutUnsupportedUrls = new StringBuffer();
        while (urls.find()) {
            var link = canonicalFordUrl(urls.group());
            var replacement = link.filter(trusted::contains).orElse("");
            urls.appendReplacement(withoutUnsupportedUrls, Matcher.quoteReplacement(replacement));
        }
        urls.appendTail(withoutUnsupportedUrls);
        return withoutUnsupportedUrls.toString();
    }

    private static String label(String url) {
        var path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
        if (path.contains("/comparativo/")) return "Comparativa";
        if (path.contains("promoc")) return "Promociones";
        if (path.contains("ficha") || path.contains("especific")) return "Ficha técnica";
        return "Página del modelo";
    }
}
