package com.ford.service.conversations.application;

import com.ford.service.conversations.api.SendMessageRequest;
import com.ford.service.inventoryapproval.api.InventoryApprovalMessagePort;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Sends only exact text supplied by a successfully verified advisor approval. */
@Component
public class ConversationInventoryApprovalMessageAdapter implements InventoryApprovalMessagePort {
    private final ConversationCommandService commands;

    public ConversationInventoryApprovalMessageAdapter(ConversationCommandService commands) {
        this.commands = commands;
    }

    @Override
    public boolean sendApprovedMessage(AuthenticatedOperator operator, UUID tenantId, UUID conversationId,
                                       long expectedConversationVersion, String idempotencyKey, String exactText) {
        var receipt = commands.sendMessage(operator, tenantId, conversationId, expectedConversationVersion,
                idempotencyKey, new SendMessageRequest(exactText, null, false));
        return receipt != null && receipt.accepted();
    }
}
