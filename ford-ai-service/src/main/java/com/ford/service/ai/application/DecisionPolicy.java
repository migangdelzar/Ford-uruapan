package com.ford.service.ai.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DecisionPolicy {

    private static final Map<ToolFamily, Set<String>> TOOL_NAMES = Map.of(
            ToolFamily.VEHICLE_SEARCH, Set.of("search_vehicles", "get_vehicle", "compare_vehicles", "vehicle_snapshot_search"),
            ToolFamily.VEHICLE_DETAILS, Set.of("search_vehicles", "get_vehicle", "compare_vehicles", "get_vehicle_provenance", "vehicle_snapshot_search"),
            ToolFamily.OFFERS, Set.of("search_offers", "list_promotions", "get_catalog_status"),
            ToolFamily.FINANCE_ESTIMATE, Set.of("finance_estimate"),
            ToolFamily.KNOWLEDGE_SEARCH, Set.of("approved_knowledge_search", "search_vehicles", "get_vehicle",
                    "get_vehicle_provenance", "vehicle_snapshot_search"),
            ToolFamily.APPOINTMENT, Set.of());

    public boolean mayCreateHandoff(ConversationDecision decision, boolean handoffEnabled) {
        return handoffEnabled && decision != null
                && decision.handoffRecommendation() != HandoffRecommendation.NONE
                && decision.handoffRecommendation() != HandoffRecommendation.LOW_CONFIDENCE;
    }

    public Set<String> allowedToolNames(ConversationDecision decision, Set<String> availableTools) {
        if (availableTools == null) {
            return Set.of();
        }
        if (decision == null || decision.toolFamily() == ToolFamily.NONE) {
            // A rules/low-confidence classifier may not know the exact family yet.
            // Keep the model fail-safe (read-only tools only), but let it search the
            // Ford catalog, offers, and approved knowledge before answering.
            return Set.copyOf(availableTools);
        }
        var allowed = TOOL_NAMES.getOrDefault(decision.toolFamily(), Set.of());
        return availableTools.stream()
                .filter(name -> name != null && allowed.contains(name.toLowerCase(Locale.ROOT)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean shouldUseRestrictedProvider(ConversationDecision decision) {
        return decision != null && decision.sensitiveDataLevel() == SensitiveDataLevel.CONFIRMED;
    }

    public boolean requiresVolatileInformation(ConversationDecision decision) {
        return decision != null && (decision.toolFamily() == ToolFamily.VEHICLE_SEARCH
                || decision.toolFamily() == ToolFamily.VEHICLE_DETAILS
                || decision.toolFamily() == ToolFamily.OFFERS
                || decision.toolFamily() == ToolFamily.KNOWLEDGE_SEARCH);
    }
}
