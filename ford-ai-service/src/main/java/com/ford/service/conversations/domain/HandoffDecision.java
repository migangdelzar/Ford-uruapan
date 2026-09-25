package com.ford.service.conversations.domain;

public record HandoffDecision(String reason) {
    public HandoffDecision {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Handoff reason is required");
        }
    }
}
