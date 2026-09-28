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
import com.ford.service.ai.application.LeadNextAction;
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
        var urgency = urgency(normalized, intent);
        var nextAction = nextAction(intent, urgency);

        return new ConversationDecision(intent, LeadTemperature.UNKNOWN, LeadStage.NEW, handoff,
                toolFamily, sensitive, nextAction, urgency, summary(intent, message), List.of(
                        evidence(ClassifierType.INTENT, intent.name(), "rules_intent"),
                        evidence(ClassifierType.LEAD, "NEW/UNKNOWN", "rules_lead"),
                        evidence(ClassifierType.HANDOFF, handoff.name(), "rules_handoff"),
                        evidence(ClassifierType.TOOL_FAMILY, toolFamily.name(), "rules_tool_family"),
                        evidence(ClassifierType.SENSITIVE_DATA, sensitive.name(), "rules_sensitive_data")));
    }

    private static LeadNextAction nextAction(ConversationIntent intent, boolean urgency) {
        if (intent == ConversationIntent.HUMAN_REQUEST) return LeadNextAction.CONNECT_ADVISOR;
        if (intent == ConversationIntent.APPOINTMENT || urgency && intent == ConversationIntent.VEHICLE_DETAILS) {
            return LeadNextAction.SCHEDULE_TEST_DRIVE;
        }
        if (intent == ConversationIntent.FINANCE) return LeadNextAction.SEND_QUOTE;
        if (intent != ConversationIntent.GENERAL) return LeadNextAction.FOLLOW_UP;
        return LeadNextAction.NO_ACTION;
    }

    private static boolean urgency(String normalized, ConversationIntent intent) {
        return intent == ConversationIntent.APPOINTMENT || containsAny(normalized,
                "hoy", "ahora", "urgente", "esta semana", "lo antes posible");
    }

    private static String summary(ConversationIntent intent, String message) {
        var clean = message == null ? "" : message.trim().replaceAll("\\s+", " ");
        if (clean.length() > 240) clean = clean.substring(0, 240).trim();
        return clean.isBlank() ? intent.name().toLowerCase(Locale.ROOT) : clean;
    }

    private static ConversationIntent toIntent(Intent intent) {
        return switch (intent) {
            case INVENTORY -> ConversationIntent.INVENTORY;
            case FINANCE -> ConversationIntent.FINANCE;
            case OFFERS -> ConversationIntent.OFFERS;
            case KNOWLEDGE -> ConversationIntent.KNOWLEDGE;
            case CATALOG -> ConversationIntent.VEHICLE_DETAILS;
            case LEAD_HANDOFF -> ConversationIntent.HUMAN_REQUEST;
            case APPOINTMENT -> ConversationIntent.APPOINTMENT;
            case TRADE_IN -> ConversationIntent.TRADE_IN;
            case DEALERSHIP_INFO -> ConversationIntent.DEALERSHIP_INFO;
            case SERVICE_SUPPORT -> ConversationIntent.SERVICE_SUPPORT;
            case COMPLAINT_STATUS -> ConversationIntent.COMPLAINT_STATUS;
            case OUT_OF_SCOPE -> ConversationIntent.OUT_OF_SCOPE;
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
            case APPOINTMENT -> ToolFamily.APPOINTMENT;
            case LEAD_HANDOFF, TRADE_IN, DEALERSHIP_INFO, SERVICE_SUPPORT, COMPLAINT_STATUS,
                    OUT_OF_SCOPE, GENERAL -> ToolFamily.NONE;
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
