package com.ford.service.ai.api;

import com.ford.service.ai.application.Intent;
import com.ford.service.ai.application.ConversationDecision;

import java.util.List;
import java.util.Objects;

public record AssistantTurn(
        String text,
        Intent intent,
        boolean requestsHuman,
        List<String> sourceIds,
        String provider,
        String modelVersion,
        ConversationDecision decision) {

    public AssistantTurn {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Assistant turn text is required");
        }
        Objects.requireNonNull(intent, "intent is required");
        sourceIds = List.copyOf(sourceIds == null ? List.of() : sourceIds);
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        modelVersion = modelVersion == null || modelVersion.isBlank() ? "unknown" : modelVersion;
        decision = decision == null ? ConversationDecision.deterministicDefault() : decision;
    }

    public AssistantTurn(String text, Intent intent, boolean requestsHuman,
                         List<String> sourceIds, String provider, String modelVersion) {
        this(text, intent, requestsHuman, sourceIds, provider, modelVersion,
                ConversationDecision.deterministicDefault());
    }
}
