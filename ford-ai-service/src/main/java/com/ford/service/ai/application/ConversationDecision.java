package com.ford.service.ai.application;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record ConversationDecision(
        ConversationIntent intent,
        LeadTemperature leadTemperature,
        LeadStage leadStage,
        HandoffRecommendation handoffRecommendation,
        ToolFamily toolFamily,
        SensitiveDataLevel sensitiveDataLevel,
        List<ClassificationEvidence> evidence) {

    public ConversationDecision {
        Objects.requireNonNull(intent, "intent is required");
        Objects.requireNonNull(leadTemperature, "lead temperature is required");
        Objects.requireNonNull(leadStage, "lead stage is required");
        Objects.requireNonNull(handoffRecommendation, "handoff recommendation is required");
        Objects.requireNonNull(toolFamily, "tool family is required");
        Objects.requireNonNull(sensitiveDataLevel, "sensitive data level is required");
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
    }

    public Intent toLegacyIntent() {
        return switch (intent) {
            case INVENTORY -> Intent.INVENTORY;
            case VEHICLE_DETAILS -> Intent.CATALOG;
            case FINANCE -> Intent.FINANCE;
            case OFFERS -> Intent.OFFERS;
            case KNOWLEDGE -> Intent.KNOWLEDGE;
            case HUMAN_REQUEST -> Intent.LEAD_HANDOFF;
            case GENERAL, APPOINTMENT -> Intent.GENERAL;
        };
    }

    public String toPromptContext() {
        return "[DECISION CONTEXT]\n" +
                "intent=" + lower(intent) + "\n" +
                "lead_stage=" + lower(leadStage) + "\n" +
                "lead_temperature=" + lower(leadTemperature) + "\n" +
                "tool_family=" + lower(toolFamily) + "\n" +
                "handoff=" + lower(handoffRecommendation) + "\n" +
                "sensitive_data=" + lower(sensitiveDataLevel) + "\n" +
                "[/DECISION CONTEXT]";
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
