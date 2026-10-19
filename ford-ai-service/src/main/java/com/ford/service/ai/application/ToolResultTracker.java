package com.ford.service.ai.application;

import com.ford.service.ai.api.AssistantTurnUnavailableException;

import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-generation record of read-only tool calls that returned a non-empty result. */
public final class ToolResultTracker {

    private final Set<String> attempted = ConcurrentHashMap.newKeySet();
    private final Set<String> returnedResults = ConcurrentHashMap.newKeySet();
    private final Map<String, AssistantTurnUnavailableException.Disposition> failed = new ConcurrentHashMap<>();
    private final Map<String, String> returnedPayloads = new ConcurrentHashMap<>();
    private static final Set<String> FACT_TOOLS = Set.of(
            "get_vehicle", "search_vehicles", "list_promotions", "search_offers",
            "compare_vehicles", "compare_trims", "compare_model_years", "get_variant_comparisons",
            "get_page_links", "get_vehicle_provenance");
    private static final int MAX_CAPTURED_PAYLOAD_CHARS = 64_000;

    public void attempted(String toolName) {
        if (toolName != null && !toolName.isBlank()) attempted.add(toolName);
    }

    public void resultReturned(String toolName, String result) {
        if (toolName == null || toolName.isBlank()) return;
        if (result != null && !result.isBlank()) {
            returnedResults.add(toolName);
            if (FACT_TOOLS.contains(toolName) && result.length() <= MAX_CAPTURED_PAYLOAD_CHARS) {
                returnedPayloads.put(toolName, result);
            }
        }
    }

    public void failed(String toolName, AssistantTurnUnavailableException.Disposition disposition) {
        if (toolName != null && !toolName.isBlank()) {
            failed.put(toolName, disposition == null
                    ? AssistantTurnUnavailableException.Disposition.REVIEW : disposition);
        }
    }

    public boolean hasReturnedResult() {
        return !returnedResults.isEmpty();
    }

    public Set<String> returnedToolNames() {
        return Set.copyOf(returnedResults);
    }

    public Map<String, String> returnedPayloads() {
        return Map.copyOf(returnedPayloads);
    }

    public boolean hasAttempted() { return !attempted.isEmpty(); }

    public boolean hasFailed() { return !failed.isEmpty(); }

    public Set<String> failedToolNames() { return Set.copyOf(failed.keySet()); }

    public AssistantTurnUnavailableException.Disposition failureDisposition() {
        return failed.values().stream().anyMatch(disposition ->
                        disposition == AssistantTurnUnavailableException.Disposition.REVIEW)
                ? AssistantTurnUnavailableException.Disposition.REVIEW
                : AssistantTurnUnavailableException.Disposition.RETRY;
    }
}
