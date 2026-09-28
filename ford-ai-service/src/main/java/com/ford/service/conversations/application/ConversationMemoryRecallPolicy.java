package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryContext;

import java.util.Locale;
import java.util.Objects;

public final class ConversationMemoryRecallPolicy {

    private static final int MAX_RECALL_LIMIT = 3;

    public boolean shouldRecall(String query, ConversationMemoryContext context) {
        Objects.requireNonNull(context, "context");
        if (query == null || query.isBlank()) {
            return false;
        }
        var normalized = query.toLowerCase(Locale.ROOT).trim();
        return containsAny(normalized,
                "había dicho", "habia dicho", "le dije", "te dije", "antes", "anteriormente",
                "recordar", "recuerda", "recuerdas", "qué modelo pregunté", "que modelo pregunte",
                "what did i say", "what did i tell you", "before", "previously", "earlier",
                "do you remember", "remember when", "my budget", "my preference");
    }

    public int limit(int requestedLimit) {
        if (requestedLimit < 1) {
            throw new IllegalArgumentException("requestedLimit must be positive");
        }
        return Math.min(requestedLimit, MAX_RECALL_LIMIT);
    }

    private static boolean containsAny(String value, String... phrases) {
        for (var phrase : phrases) {
            if (value.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
