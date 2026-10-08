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
            if (classified.toolFamily() == ToolFamily.NONE) {
                var route = rules.decide(context);
                if (route.toolFamily().requiresVolatileInformation()) {
                    log.warn("Conversation volatile tool route corrected: conversationId={} classifiedIntent={} correctedIntent={} correctedToolFamily={}",
                            context.conversationId(), classified.intent(), route.intent(), route.toolFamily());
                    return classified.withVolatileToolRoute(route);
                }
            }
            return classified;
        } catch (RuntimeException ignored) {
            log.warn("Conversation classification failed; using rules fallback: exceptionType={}",
                    ignored.getClass().getSimpleName());
            return rules.decide(context);
        }
    }
}
