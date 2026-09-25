package com.ford.service.ai.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DecisionPolicy {

    private static final Map<ToolFamily, Set<String>> TOOL_NAMES = Map.of(
            ToolFamily.VEHICLE_SEARCH, Set.of("vehicle_snapshot_search"),
            ToolFamily.VEHICLE_DETAILS, Set.of("vehicle_snapshot_search"),
            ToolFamily.FINANCE_ESTIMATE, Set.of("finance_estimate"),
            ToolFamily.KNOWLEDGE_SEARCH, Set.of("approved_knowledge_search"),
            ToolFamily.APPOINTMENT, Set.of());

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
