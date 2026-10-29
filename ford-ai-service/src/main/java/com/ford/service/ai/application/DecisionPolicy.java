package com.ford.service.ai.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DecisionPolicy {

    private static final Map<ToolFamily, Set<String>> TOOL_NAMES = Map.of(
            ToolFamily.VEHICLE_SEARCH, Set.of("search_vehicles", "search_vehicle_and_offers", "get_vehicle",
                    "compare_vehicles", "vehicle_snapshot_search"),
            ToolFamily.VEHICLE_DETAILS, Set.of("search_vehicles", "get_vehicle", "compare_vehicles",
                    "get_vehicle_provenance", "get_page_links", "compare_trims", "compare_model_years",
                    "get_variant_comparisons", "vehicle_snapshot_search"),
            ToolFamily.OFFERS, Set.of("search_offers", "search_vehicle_and_offers", "list_promotions",
                    "get_catalog_status"),
            ToolFamily.FINANCE_ESTIMATE, Set.of("finance_estimate", "list_finance_products", "get_vehicle"),
            ToolFamily.KNOWLEDGE_SEARCH, Set.of("approved_knowledge_search", "search_vehicles", "get_vehicle",
                    "get_vehicle_provenance", "get_page_links", "vehicle_snapshot_search"),
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
            return Set.of();
        }
        var allowed = TOOL_NAMES.getOrDefault(decision.toolFamily(), Set.of());
        if (decision.toolFamily() == ToolFamily.VEHICLE_DETAILS
                && decision.vehicleEntities().model().isPresent()
                && decision.vehicleEntities().trim().isPresent()) {
            allowed = allowed.stream().filter(name -> !"search_vehicles".equals(name))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        var allowedTools = allowed;
        return availableTools.stream()
                .filter(name -> name != null && allowedTools.contains(name.toLowerCase(Locale.ROOT)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean shouldUseRestrictedProvider(ConversationDecision decision) {
        return decision != null && decision.sensitiveDataLevel() == SensitiveDataLevel.CONFIRMED;
    }

    public boolean requiresVolatileInformation(ConversationDecision decision) {
        return decision != null && decision.toolFamily().requiresVolatileInformation();
    }
}
