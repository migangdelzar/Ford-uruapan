package com.ford.service.followups.api;

import org.springframework.modulith.NamedInterface;

import java.util.UUID;

@NamedInterface
public interface LeadFollowUpTurnTracker {
    void processTurn(UUID tenantId, UUID conversationId, String inboundMessageId,
                     String leadTemperature, String inboundText, String assistantText);
}
