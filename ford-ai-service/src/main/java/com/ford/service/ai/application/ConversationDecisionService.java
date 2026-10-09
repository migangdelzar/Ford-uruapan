package com.ford.service.ai.application;

import com.ford.service.ai.configuration.ClassificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Objects;

public final class ConversationDecisionService {

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

    public ConversationDecision decide(ClassificationContext context) {
        Objects.requireNonNull(context, "classification context is required");
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
            return applyDeterministicToolRoute(context, classified);
        } catch (RuntimeException ignored) {
            log.warn("Conversation classification failed; using rules fallback: exceptionType={}",
                    ignored.getClass().getSimpleName());
            return rules.decide(context);
        }
    }

    private ConversationDecision applyDeterministicToolRoute(ClassificationContext context,
                                                               ConversationDecision classified) {
        var routed = rules.decide(context);
        if (routed.toolFamily() == ToolFamily.NONE) {
            return classified;
        }
        var evidence = new ArrayList<>(classified.evidence());
        evidence.removeIf(item -> item.classifier() == ClassifierType.INTENT
                || item.classifier() == ClassifierType.TOOL_FAMILY);
        routed.evidence().stream()
                .filter(item -> item.classifier() == ClassifierType.INTENT
                        || item.classifier() == ClassifierType.TOOL_FAMILY)
                .forEach(evidence::add);
        return new ConversationDecision(routed.intent(), classified.leadTemperature(), classified.leadStage(),
                classified.handoffRecommendation(), routed.toolFamily(), classified.sensitiveDataLevel(), evidence);
    }
}
