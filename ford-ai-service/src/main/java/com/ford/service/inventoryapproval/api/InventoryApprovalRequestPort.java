package com.ford.service.inventoryapproval.api;

import java.util.UUID;

/** Safe inbound request boundary used by the conversation workflow. */
public interface InventoryApprovalRequestPort {
    void requestInventory(UUID tenantId, UUID conversationId, String model, Integer year,
                          String trim, String idempotencyKey);
}
