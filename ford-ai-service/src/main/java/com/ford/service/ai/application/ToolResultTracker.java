package com.ford.service.ai.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-generation record of read-only tool calls that returned a non-empty result. */
public final class ToolResultTracker {

    private final Set<String> attempted = ConcurrentHashMap.newKeySet();
    private final Set<String> returnedResults = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public void attempted(String toolName) {
        if (toolName != null && !toolName.isBlank()) attempted.add(toolName);
    }

    public void resultReturned(String toolName, String result) {
        if (toolName == null || toolName.isBlank()) return;
        if (result != null && !result.isBlank()) returnedResults.add(toolName);
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
