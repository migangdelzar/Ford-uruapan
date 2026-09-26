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
        LeadNextAction nextAction,
        boolean urgencySignal,
        String summary,
        List<ClassificationEvidence> evidence) {

    public ConversationDecision(ConversationIntent intent,
                                LeadTemperature leadTemperature,
                                LeadStage leadStage,
                                HandoffRecommendation handoffRecommendation,
                                ToolFamily toolFamily,
                                SensitiveDataLevel sensitiveDataLevel,
                                List<ClassificationEvidence> evidence) {
        this(intent, leadTemperature, leadStage, handoffRecommendation, toolFamily,
                sensitiveDataLevel, LeadNextAction.NO_ACTION, false, "", evidence);
    }

    public static ConversationDecision deterministicDefault() {
        return new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN, LeadStage.NEW,
                HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of());
    }

    public ConversationDecision {
        Objects.requireNonNull(intent, "intent is required");
        Objects.requireNonNull(leadTemperature, "lead temperature is required");
        Objects.requireNonNull(leadStage, "lead stage is required");
        Objects.requireNonNull(handoffRecommendation, "handoff recommendation is required");
        Objects.requireNonNull(toolFamily, "tool family is required");
        Objects.requireNonNull(sensitiveDataLevel, "sensitive data level is required");
        Objects.requireNonNull(nextAction, "next action is required");
        summary = normalizeSummary(summary);
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

    public ConversationDecision withLeadClassification(LeadStage stage,
                                                       LeadTemperature temperature,
                                                       ClassificationEvidence leadEvidence) {
        var updatedEvidence = new java.util.ArrayList<>(evidence);
        updatedEvidence.removeIf(evidence -> evidence.classifier() == ClassifierType.LEAD
                && "deterministic_lead_signal_score".equals(evidence.reasonCode()));
        if (leadEvidence != null) {
            updatedEvidence.add(leadEvidence);
        }
        return new ConversationDecision(intent, temperature, stage, handoffRecommendation,
                toolFamily, sensitiveDataLevel, nextAction, urgencySignal, summary, updatedEvidence);
    }

    public ConversationDecision withHandoffRecommendation(HandoffRecommendation recommendation) {
        return new ConversationDecision(intent, leadTemperature, leadStage, recommendation,
                toolFamily, sensitiveDataLevel, nextAction, urgencySignal, summary, evidence);
    }

    public String toPromptContext() {
        return "[DECISION CONTEXT]\n" +
                "intent=" + lower(intent) + "\n" +
                "lead_stage=" + lower(leadStage) + "\n" +
                "lead_temperature=" + lower(leadTemperature) + "\n" +
                "tool_family=" + lower(toolFamily) + "\n" +
                "handoff=" + lower(handoffRecommendation) + "\n" +
                "sensitive_data=" + lower(sensitiveDataLevel) + "\n" +
                "next_action=" + lower(nextAction) + "\n" +
                "urgency_signal=" + urgencySignal + "\n" +
                "summary=" + summary + "\n" +
                "[/DECISION CONTEXT]";
    }

    private static String normalizeSummary(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 280 ? normalized : normalized.substring(0, 280).trim();
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
