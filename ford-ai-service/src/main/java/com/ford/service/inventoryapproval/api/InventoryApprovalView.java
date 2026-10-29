package com.ford.service.inventoryapproval.api;

import com.ford.service.inventoryapproval.domain.InventoryApproval;
import com.ford.service.inventoryapproval.domain.InventoryApprovalStatus;
import com.ford.service.inventoryapproval.domain.InventoryOutcome;

import java.time.Instant;
import java.util.UUID;

public record InventoryApprovalView(UUID id, UUID conversationId, String model, Integer year, String trim,
                                    InventoryApprovalStatus status, InventoryOutcome outcome,
                                    Integer verifiedCount, Instant verifiedAt, Instant verificationExpiresAt,
                                    String draftText, String approvedText, UUID verifiedBy, UUID approvedBy,
                                    long version, Instant createdAt) {
    public static InventoryApprovalView from(InventoryApproval approval) {
        return new InventoryApprovalView(approval.id(), approval.conversationId(), approval.model(),
                approval.year(), approval.trim(), approval.status(), approval.outcome(),
                approval.verifiedCount(), approval.verifiedAt(), approval.verificationExpiresAt(),
                approval.draftText(), approval.approvedText(), approval.verifiedBy(), approval.approvedBy(),
                approval.version(), approval.createdAt());
    }
}
