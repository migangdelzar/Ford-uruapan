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
    private final Map<String, Integer> evidenceCounts = new ConcurrentHashMap<>();
    // Retrieval evidence is not a model-selected tool execution; never add it to attempted/snapshot.
    private final Map<String, String> prefetchedPayloads = new ConcurrentHashMap<>();
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
            evidenceCounts.put(toolName, evidenceCount(result));
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
        return !returnedResults.isEmpty() || hasPrefetchedEvidence();
    }

    public boolean hasEvidence() {
        return hasPrefetchedEvidence() || evidenceCounts.values().stream().anyMatch(count -> count != null && count > 0);
    }

    public boolean acceptPrefetchedComparison(com.ford.service.ai.api.AssistantRequest request,
                                               ToolContext context, Set<String> authorizedTools) {
        var evidence = context == null ? null : context.prefetchedComparison();
        if (evidence == null || authorizedTools == null || !authorizedTools.contains("compare_trims")
                || !evidence.validFor(request, context, java.time.Instant.now())) return false;
        prefetchedPayloads.put("compare_trims", evidence.payload());
        return true;
    }

    public boolean hasPrefetchedEvidence() { return !prefetchedPayloads.isEmpty(); }

    public boolean hasAmbiguousVehicleTrim() {
        return ambiguousVehicleTrim().isPresent();
    }

    public java.util.Optional<AmbiguousVehicleTrimResult> ambiguousVehicleTrim() {
        var payload = returnedPayloads.get("get_vehicle");
        if (payload == null) return java.util.Optional.empty();
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            var choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() < 2
                    || !root.hasNonNull("modelYear") || !root.hasNonNull("model")) {
                return java.util.Optional.empty();
            }
            var model = root.get("model").asText();
            var year = root.get("modelYear").asInt(-1);
            var candidates = new java.util.ArrayList<VehicleTrimCandidate>();
            for (var choice : choices) {
                if (!choice.isObject() || !choice.path("trim").isTextual()) return java.util.Optional.empty();
                candidates.add(new VehicleTrimCandidate(model, year, choice.path("trim").asText()));
            }
            return java.util.Optional.of(new AmbiguousVehicleTrimResult(model, year, candidates));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ignored) {
            return java.util.Optional.empty();
        }
    }

    public Set<String> returnedToolNames() {
        return Set.copyOf(returnedResults);
    }

    public Map<String, String> returnedPayloads() {
        var payloads = new java.util.HashMap<>(prefetchedPayloads);
        payloads.putAll(returnedPayloads);
        return Map.copyOf(payloads);
    }

    public boolean hasAttempted() { return !attempted.isEmpty(); }

    public boolean hasAttempted(String toolName) {
        return toolName != null && attempted.contains(toolName);
    }

    public boolean hasEvidenceFrom(String toolName) {
        return toolName != null && evidenceCounts.getOrDefault(toolName, 0) > 0;
    }

    public boolean hasFailed() { return !failed.isEmpty(); }

    public Set<String> failedToolNames() { return Set.copyOf(failed.keySet()); }

    public AssistantTurnUnavailableException.Disposition failureDisposition() {
        return failed.values().stream().anyMatch(disposition ->
                        disposition == AssistantTurnUnavailableException.Disposition.REVIEW)
                ? AssistantTurnUnavailableException.Disposition.REVIEW
                : AssistantTurnUnavailableException.Disposition.RETRY;
    }

    public ToolExecutionSnapshot snapshot() {
        var calls = attempted.stream().sorted().map(toolName -> {
            if (failed.containsKey(toolName)) {
                var disposition = failed.get(toolName);
                return new ToolExecutionSnapshot.ToolCall(toolName, ToolExecutionSnapshot.Status.FAILED,
                        disposition.name().toLowerCase(java.util.Locale.ROOT), null);
            }
            if (returnedResults.contains(toolName)) {
                return new ToolExecutionSnapshot.ToolCall(toolName, ToolExecutionSnapshot.Status.RETURNED,
                        "tool_returned", evidenceCounts.get(toolName));
            }
            return new ToolExecutionSnapshot.ToolCall(toolName, ToolExecutionSnapshot.Status.EMPTY,
                    "tool_return_missing", null);
        }).toList();
        return new ToolExecutionSnapshot(calls);
    }

    public static int evidenceCount(String result) {
        if (result == null || result.isBlank()) return 0;
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result);
            if (root.isArray()) return root.size();
            for (var field : java.util.List.of("results", "offers", "promotions", "evidence", "comparisons", "facts")) {
                var value = root.get(field);
                if (value != null && value.isArray()) return value.size();
            }
            if (root.isObject() && !root.isEmpty() && hasSource(root)) return 1;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return 0;
        }
        return 0;
    }

    private static boolean hasSource(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) return false;
        if (node.isObject()) {
            for (var field = node.fields(); field.hasNext();) {
                var entry = field.next();
                if (Set.of("sourceUrl", "source_url", "modelPageUrl", "model_page_url", "priceSourceUrl",
                        "price_source_url", "technicalSheetUrl", "technical_sheet_url").contains(entry.getKey())
                        && entry.getValue().isTextual() && !entry.getValue().asText().isBlank()) return true;
                if (hasSource(entry.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (var child : node) if (hasSource(child)) return true;
        }
        return false;
    }
}
