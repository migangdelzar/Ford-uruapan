package com.ford.service.conversations.application;

import com.ford.service.conversations.api.ConversationHandoffMessagePort;
import com.ford.service.conversations.application.port.out.OperatorConversationCommandRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DefaultConversationHandoffMessageService implements ConversationHandoffMessagePort {

    private static final String TAKEOVER_MESSAGE =
            "Hola, he tomado su conversación. Puede escribirme por aquí.";

    private final OperatorConversationCommandRepository commands;

    public DefaultConversationHandoffMessageService(OperatorConversationCommandRepository commands) {
        this.commands = commands;
    }

    @Override
    public void sendAdvisorTakeoverMessage(UUID tenantId, UUID conversationId,
                                           UUID operatorId, UUID handoffTicketId) {
        var providerMessageId = "operator:handoff:" + handoffTicketId;
        if (commands.messageExists(tenantId, conversationId, providerMessageId)) {
            return;
        }
        var target = commands.find(tenantId, conversationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("conversation not found"));
        commands.appendMessage(new OperatorConversationCommandRepository.CommandMessage(
                tenantId, conversationId, operatorId, UUID.randomUUID(), providerMessageId,
                TAKEOVER_MESSAGE, false, target.version()));
    }
}
