package com.ford.service.conversations.api;

import org.springframework.modulith.NamedInterface;

import java.util.UUID;

@NamedInterface
public interface ConversationHandoffMessagePort {

    void sendAdvisorTakeoverMessage(UUID tenantId, UUID conversationId, UUID operatorId, UUID handoffTicketId);
}
