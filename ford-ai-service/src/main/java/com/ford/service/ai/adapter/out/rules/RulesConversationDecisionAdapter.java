package com.ford.service.ai.adapter.out.rules;

import com.ford.service.ai.application.ClassificationContext;
import com.ford.service.ai.application.ClassificationEvidence;
import com.ford.service.ai.application.ClassifierType;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationDecisionPort;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.Intent;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RulesConversationDecisionAdapter implements ConversationDecisionPort {

    private final IntentRouter intentRouter;

    public RulesConversationDecisionAdapter(IntentRouter intentRouter) {
        this.intentRouter = Objects.requireNonNull(intentRouter);
    }

    @Override
    public ConversationDecision decide(ClassificationContext context) {
        Objects.requireNonNull(context, "classification context is required");
        var message = context.message() == null ? "" : context.message();
        var normalized = message.toLowerCase(Locale.ROOT);
        var legacyIntent = intentRouter.route(message);
        var intent = toIntent(legacyIntent);
        var handoff = handoff(normalized, intent);
        var sensitive = sensitive(context, normalized);
        var toolFamily = toolFamily(legacyIntent);

        return new ConversationDecision(intent, LeadTemperature.UNKNOWN, LeadStage.NEW, handoff,
                toolFamily, sensitive, List.of(
                        evidence(ClassifierType.INTENT, intent.name(), "rules_intent"),
                        evidence(ClassifierType.LEAD, "NEW/UNKNOWN", "rules_lead"),
                        evidence(ClassifierType.HANDOFF, handoff.name(), "rules_handoff"),
                        evidence(ClassifierType.TOOL_FAMILY, toolFamily.name(), "rules_tool_family"),
                        evidence(ClassifierType.SENSITIVE_DATA, sensitive.name(), "rules_sensitive_data")));
    }

    private static ConversationIntent toIntent(Intent intent) {
        return switch (intent) {
            case INVENTORY -> ConversationIntent.INVENTORY;
            case FINANCE -> ConversationIntent.FINANCE;
            case OFFERS -> ConversationIntent.OFFERS;
            case KNOWLEDGE -> ConversationIntent.KNOWLEDGE;
            case CATALOG -> ConversationIntent.VEHICLE_DETAILS;
            case LEAD_HANDOFF -> ConversationIntent.HUMAN_REQUEST;
            case GENERAL -> ConversationIntent.GENERAL;
        };
    }

    private static ToolFamily toolFamily(Intent intent) {
        return switch (intent) {
            case INVENTORY -> ToolFamily.VEHICLE_SEARCH;
            case CATALOG -> ToolFamily.VEHICLE_DETAILS;
            case OFFERS -> ToolFamily.OFFERS;
            case FINANCE -> ToolFamily.FINANCE_ESTIMATE;
            case KNOWLEDGE -> ToolFamily.KNOWLEDGE_SEARCH;
            default -> ToolFamily.NONE;
        };
    }

    private static HandoffRecommendation handoff(String normalized, ConversationIntent intent) {
        if (intent == ConversationIntent.HUMAN_REQUEST) {
            return HandoffRecommendation.USER_REQUESTED;
        }
        if (containsAny(normalized, "queja", "molesto", "molesta", "pésimo", "pesimo")) {
            return HandoffRecommendation.NEGATIVE_SENTIMENT;
        }
        return HandoffRecommendation.NONE;
    }

    private static SensitiveDataLevel sensitive(ClassificationContext context, String normalized) {
        if (context.containsSensitiveData() || containsAny(normalized,
                "contraseña", "contrasena", "password", "token", "cvv", "nip",
                "número de tarjeta", "numero de tarjeta", "tarjeta de crédito", "tarjeta de credito")) {
            return SensitiveDataLevel.CONFIRMED;
        }
        return SensitiveDataLevel.NONE;
    }

    private static ClassificationEvidence evidence(ClassifierType classifier, String classification, String reason) {
        return new ClassificationEvidence(classifier, classification, 1.0, reason,
                "rules", "deterministic", 0, true);
    }

    private static boolean containsAny(String value, String... terms) {
        for (var term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
