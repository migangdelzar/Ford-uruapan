package com.ford.service.conversations.api;

import com.ford.service.ai.application.ConversationDecision;

public record AssistantReply(String text, boolean handoffRequired, ConversationDecision decision) {
    public AssistantReply {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Assistant reply text is required");
        }
        decision = decision == null ? ConversationDecision.deterministicDefault() : decision;
    }

    public AssistantReply(String text, boolean handoffRequired) {
        this(text, handoffRequired, ConversationDecision.deterministicDefault());
    }
}
