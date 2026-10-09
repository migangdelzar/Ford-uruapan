package com.ford.service.ai.application;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adds only Ford Uruapan links returned by tools and removes tracking parameters. */
@Component
public final class GroundedSourceLinkRenderer {

    private static final Pattern URL = Pattern.compile("https?://[^\\s\\]}>\\\"']+");

    public String render(String answer, List<String> toolResults) {
        var text = answer == null ? "" : answer.strip();
        var links = links(toolResults);
        var withoutUnsupportedFordLinks = stripUnsupportedFordLinks(text, links);
        var existing = new LinkedHashSet<>(findFordLinks(withoutUnsupportedFordLinks));
        var citations = links.stream().filter(link -> !existing.contains(link)).toList();
        if (citations.isEmpty()) {
            return withoutUnsupportedFordLinks.strip();
        }
        var sourceText = citations.stream()
                .map(link -> "[" + label(link) + "](" + link + ")")
                .reduce((left, right) -> left + " · " + right)
                .orElse("");
        return (withoutUnsupportedFordLinks.isBlank() ? "" : withoutUnsupportedFordLinks.strip() + "\n")
                + "Fuentes: " + sourceText;
    }

    private static List<String> links(List<String> toolResults) {
        var links = new LinkedHashSet<String>();
        if (toolResults == null) {
            return List.of();
        }
        for (var result : toolResults) {
            if (result == null) {
                continue;
            }
            var matcher = URL.matcher(result);
            while (matcher.find()) {
                normalize(matcher.group()).ifPresent(links::add);
            }
        }
        return List.copyOf(links);
    }

    private static List<String> findFordLinks(String text) {
        var found = new ArrayList<String>();
        var matcher = URL.matcher(text);
        while (matcher.find()) {
            normalize(matcher.group()).ifPresent(found::add);
        }
        return found;
    }

    private static String stripUnsupportedFordLinks(String answer, List<String> verified) {
        var matcher = URL.matcher(answer);
        var result = new StringBuffer();
        while (matcher.find()) {
            var url = normalize(matcher.group());
            if (isFordUrl(matcher.group()) && (url.isEmpty() || !verified.contains(url.get()))) {
                matcher.appendReplacement(result, "");
            } else if (url.isPresent() && isFordUrl(matcher.group())) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(url.get()));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(result);
        return result.toString().replaceAll("[ \\t]+([.,;:!?])", "$1").replaceAll("[ \\t]{2,}", " ");
    }

    private static java.util.Optional<String> normalize(String value) {
        try {
            var uri = URI.create(value.replaceAll("[),.;:!?]+$", ""));
            if (!isFordUrlHost(uri.getHost()) || !"https".equalsIgnoreCase(uri.getScheme())) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT),
                    -1, uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath(), null, null).toString());
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private static boolean isFordUrl(String raw) {
        try {
            return isFordUrlHost(URI.create(raw).getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isFordUrlHost(String host) {
        return host != null && (host.equalsIgnoreCase("forduruapan.mx")
                || host.equalsIgnoreCase("www.forduruapan.mx"));
    }

    private static String label(String link) {
        var path = URI.create(link).getPath().toLowerCase(Locale.ROOT);
        if (path.contains("comparativo")) {
            return "Comparativa";
        }
        if (path.contains("ficha") || path.contains("especific")) {
            return "Ficha técnica";
        }
        if (path.contains("promoci")) {
            return "Promociones";
        }
        return "Modelo";
    }
}
