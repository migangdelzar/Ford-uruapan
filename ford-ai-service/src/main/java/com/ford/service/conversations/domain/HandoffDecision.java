package com.ford.service.conversations.domain;


public record HandoffDecision(String reason, String priority, String idempotencyKey) {
    public HandoffDecision(String reason) {
        this(reason, "NORMAL", null);
    }

    public HandoffDecision {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Handoff reason is required");
        }
        priority = priority == null ? "NORMAL" : priority;
        if (!priority.equals("LOW") && !priority.equals("NORMAL") && !priority.equals("HIGH")) {
            throw new IllegalArgumentException("Unsupported handoff priority");
        }
    }
}
