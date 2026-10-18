package com.ford.service.handoff.domain;

import com.ford.service.conversations.api.BdcHandoffPacket;

import java.util.UUID;

public record HandoffRequest(UUID tenantId, UUID conversationId, UUID leadId, String reason,
                             BdcHandoffPacket packet) {

    public HandoffRequest {
        packet = packet == null ? BdcHandoffPacket.empty() : packet;
    }

    public HandoffRequest(UUID tenantId, UUID conversationId, UUID leadId, String reason) {
        this(tenantId, conversationId, leadId, reason, BdcHandoffPacket.empty());
    }
}
