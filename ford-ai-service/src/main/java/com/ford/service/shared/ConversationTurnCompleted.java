package com.ford.service.shared;

import java.util.UUID;

public record ConversationTurnCompleted(UUID tenantId, UUID conversationId,
                                        String providerMessageId, String leadTemperature,
                                        String inboundText, String assistantText) {
}
