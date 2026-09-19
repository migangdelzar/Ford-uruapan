package com.ford.service.conversations.api;

public record AssistantReply(String text, boolean handoffRequired) {
    public AssistantReply {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Assistant reply text is required");
        }
    }
}
