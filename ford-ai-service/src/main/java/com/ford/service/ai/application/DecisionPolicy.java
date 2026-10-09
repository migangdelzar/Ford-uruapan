package com.ford.service.ai.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DecisionPolicy {

    private static final Map<ToolFamily, Set<String>> TOOL_NAMES = Map.ofEntries(
            Map.entry(ToolFamily.VEHICLE_SEARCH, Set.of("vehicle_snapshot_search", "search_vehicles")),
            Map.entry(ToolFamily.VEHICLE_DETAILS, Set.of("vehicle_snapshot_search", "get_vehicle")),
            Map.entry(ToolFamily.FINANCE_ESTIMATE, Set.of("finance_estimate")),
            Map.entry(ToolFamily.FINANCE_PLANS, Set.of("search_offers")),
            Map.entry(ToolFamily.VEHICLE_FINANCE, Set.of("search_vehicle_and_offers")),
            Map.entry(ToolFamily.PROMOTIONS, Set.of("list_promotions")),
            Map.entry(ToolFamily.KNOWLEDGE_SEARCH, Set.of("approved_knowledge_search")),
            Map.entry(ToolFamily.TRIM_COMPARISON, Set.of("compare_trims")),
            Map.entry(ToolFamily.MODEL_COMPARISON, Set.of("compare_vehicles")),
            Map.entry(ToolFamily.YEAR_COMPARISON, Set.of("compare_model_years")),
            Map.entry(ToolFamily.COMPETITOR_COMPARISON, Set.of("get_variant_comparisons")),
            Map.entry(ToolFamily.APPOINTMENT, Set.of()));

    public boolean mayCreateHandoff(ConversationDecision decision, boolean handoffEnabled) {
        return handoffEnabled && decision != null
                && decision.handoffRecommendation() != HandoffRecommendation.NONE;
    }

    public Set<String> allowedToolNames(ConversationDecision decision, Set<String> availableTools) {
        if (decision == null || availableTools == null || decision.toolFamily() == ToolFamily.NONE) {
            return Set.of();
        }
        var allowed = TOOL_NAMES.getOrDefault(decision.toolFamily(), Set.of());
        return availableTools.stream()
                .filter(name -> name != null && allowed.contains(name.toLowerCase(Locale.ROOT)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean shouldUseRestrictedProvider(ConversationDecision decision) {
        return decision != null && decision.sensitiveDataLevel() == SensitiveDataLevel.CONFIRMED;
    }
}
