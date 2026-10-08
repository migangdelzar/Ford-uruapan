package com.ford.service.ai.application;

import com.ford.service.ai.configuration.ClassificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;

public class ConversationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(ConversationDecisionService.class);

    private final ConversationDecisionPort primary;
    private final ConversationDecisionPort rules;
    private final ClassificationProperties properties;

    public ConversationDecisionService(ConversationDecisionPort primary,
                                       ConversationDecisionPort rules,
                                       ClassificationProperties properties) {
        this.primary = Objects.requireNonNull(primary);
        this.rules = Objects.requireNonNull(rules);
        this.properties = Objects.requireNonNull(properties);
    }

    public boolean hasConfidentLeadEvidence(ConversationDecision decision) {
        return decision.evidence().stream().anyMatch(evidence ->
                evidence.classifier() == ClassifierType.LEAD
                        && !evidence.fallbackUsed()
                        && evidence.confidence() >= properties.minConfidence());
    }

    public ConversationDecision decide(ClassificationContext context) {
        Objects.requireNonNull(context, "classification context is required");
        if (context.containsSensitiveData()) {
            log.info("Conversation classification using deterministic rules: conversationId={} reason=sensitive_data",
                    context.conversationId());
            return rules.decide(context);
        }
        if (!properties.enabled() || "disabled".equalsIgnoreCase(properties.mode())) {
            return rules.decide(context);
        }
        try {
            var classified = primary.decide(context);
            if ("shadow".equalsIgnoreCase(properties.mode())) {
                return rules.decide(context);
            }
            if (classified.evidence().stream()
                    .anyMatch(evidence -> !evidence.fallbackUsed()
                            && evidence.confidence() < properties.minConfidence())) {
                throw new IllegalStateException("TypeSafe classification confidence is below the configured threshold");
            }
            var route = rules.decide(context);
            var deterministicScopeDeniesTools = route.toolFamily() == ToolFamily.NONE
                    && (route.intent() == ConversationIntent.GENERAL
                    || route.intent() == ConversationIntent.OUT_OF_SCOPE);
            var deterministicIntentHasSpecificTool = switch (route.intent()) {
                case INVENTORY, VEHICLE_DETAILS, FINANCE, OFFERS, APPOINTMENT, SERVICE_SUPPORT -> true;
                case GENERAL, KNOWLEDGE, HUMAN_REQUEST, TRADE_IN, DEALERSHIP_INFO,
                        COMPLAINT_STATUS, OUT_OF_SCOPE -> false;
            };
            if (route.toolFamily() != classified.toolFamily()
                    && (deterministicIntentHasSpecificTool || deterministicScopeDeniesTools)) {
                log.warn("Conversation tool route reconciled: conversationId={} classifiedIntent={} classifiedToolFamily={} deterministicIntent={} deterministicToolFamily={}",
                        context.conversationId(), classified.intent(), classified.toolFamily(),
                        route.intent(), route.toolFamily());
                return classified.withDeterministicRoute(route);
            }
            return classified;
        } catch (RuntimeException ignored) {
            log.warn("Conversation classification failed; using rules fallback: exceptionType={}",
                    ignored.getClass().getSimpleName());
            return rules.decide(context);
        }
    }
}
