package com.ford.service.ai.application;

import java.util.Set;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/** Per-generation record of read-only tool calls that returned a non-empty result. */
public final class ToolResultTracker {

    private final Set<String> attempted = ConcurrentHashMap.newKeySet();
    private final Set<String> returnedResults = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final List<String> sourceUrls = new CopyOnWriteArrayList<>();
    private final GroundedSourceLinkRenderer sourceLinkRenderer = new GroundedSourceLinkRenderer();

    public void attempted(String toolName) {
        if (toolName != null && !toolName.isBlank()) attempted.add(toolName);
    }

    public void resultReturned(String toolName, String result) {
        if (toolName == null || toolName.isBlank()) return;
        if (result != null && !result.isBlank()) returnedResults.add(toolName);
    }

    public String sanitizeSourceUrls(String result) {
        var sanitized = sourceLinkRenderer.sanitizeToolOutput(result);
        sourceUrls.addAll(sourceLinkRenderer.trustedFordUrls(
                extractUrls(sanitized)));
        return sanitized;
    }

    public Set<String> sourceUrls() {
        return Set.copyOf(sourceUrls);
    }

    private static List<String> extractUrls(String text) {
        if (text == null || text.isBlank()) return List.of();
        var matcher = java.util.regex.Pattern.compile("(?i)https?://[^\\s\\\"'<>),;\\]}]+").matcher(text);
        var urls = new java.util.ArrayList<String>();
        while (matcher.find()) urls.add(matcher.group());
        return urls;
    }

    public void failed(String toolName) {
        if (toolName != null && !toolName.isBlank()) failed.add(toolName);
    }

    public boolean hasReturnedResult() {
        return !returnedResults.isEmpty();
    }

    public Set<String> returnedToolNames() {
        return Set.copyOf(returnedResults);
    }

    public boolean hasAttempted() { return !attempted.isEmpty(); }

    public boolean hasFailed() { return !failed.isEmpty(); }
}
