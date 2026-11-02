package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.api.ConversationTurnRequest;

public interface ConversationAssistant {
    AssistantReply respond(ConversationTurnRequest request);
}
