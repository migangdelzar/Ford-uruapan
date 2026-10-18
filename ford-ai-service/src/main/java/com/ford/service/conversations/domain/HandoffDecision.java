package com.ford.service.conversations.domain;

import com.ford.service.conversations.api.BdcHandoffPacket;

public record HandoffDecision(String reason, String priority, String idempotencyKey, BdcHandoffPacket packet) {

    public HandoffDecision(String reason) {
        this(reason, "NORMAL", null, BdcHandoffPacket.empty());
    }

    public HandoffDecision(String reason, String priority, String idempotencyKey) {
        this(reason, priority, idempotencyKey, BdcHandoffPacket.empty());
    }

    public HandoffDecision {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Handoff reason is required");
        }
        priority = priority == null ? "NORMAL" : priority;
        if (!priority.equals("LOW") && !priority.equals("NORMAL") && !priority.equals("HIGH")) {
            throw new IllegalArgumentException("Unsupported handoff priority");
        }
        packet = packet == null ? BdcHandoffPacket.empty() : packet;
    }
}
