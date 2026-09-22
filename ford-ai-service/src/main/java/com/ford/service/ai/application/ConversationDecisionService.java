package com.ford.service.ai.application;

import com.ford.service.ai.configuration.ClassificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
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
            return classified;
        } catch (RuntimeException ignored) {
            log.warn("Conversation classification failed; using rules fallback: exceptionType={}",
                    ignored.getClass().getSimpleName());
            return rules.decide(context);
        }
    }
}
