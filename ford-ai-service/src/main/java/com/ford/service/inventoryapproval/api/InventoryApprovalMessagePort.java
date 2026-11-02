package com.ford.service.inventoryapproval.api;

import com.ford.service.operator.application.AuthenticatedOperator;

import java.util.UUID;

/** Outbound boundary for queueing only advisor-approved exact text. */
public interface InventoryApprovalMessagePort {
    boolean sendApprovedMessage(AuthenticatedOperator operator, UUID tenantId, UUID conversationId,
                                long expectedConversationVersion, String idempotencyKey, String exactText);
}
