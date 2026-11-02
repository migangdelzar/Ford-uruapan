package com.ford.service.inventoryapproval.application.port.out;

import com.ford.service.inventoryapproval.domain.InventoryApproval;
import com.ford.service.inventoryapproval.domain.InventoryApprovalStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryApprovalRepository {
    InventoryApproval savePending(InventoryApproval approval);

    Optional<InventoryApproval> find(UUID tenantId, UUID approvalId);

    Optional<InventoryApproval> findByIdempotencyKey(UUID tenantId, String idempotencyKey);

    List<InventoryApproval> list(UUID tenantId, InventoryApprovalStatus status, int limit);

    Optional<InventoryApproval> transition(UUID tenantId, UUID approvalId, long expectedVersion,
                                           InventoryApprovalStatus expectedStatus,
                                           InventoryApproval replacement);

    void recordAudit(UUID tenantId, UUID approvalId, UUID actorId, String action,
                     Instant occurredAt, long approvalVersion);
}
