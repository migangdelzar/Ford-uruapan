package com.ford.service.inventoryapproval.application;

import com.ford.service.inventoryapproval.api.InventoryApprovalMessagePort;
import com.ford.service.inventoryapproval.api.InventoryApprovalRequestPort;
import com.ford.service.inventoryapproval.application.port.out.InventoryApprovalRepository;
import com.ford.service.inventoryapproval.domain.InventoryApproval;
import com.ford.service.inventoryapproval.domain.InventoryApprovalStatus;
import com.ford.service.inventoryapproval.domain.InventoryOutcome;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class InventoryApprovalService implements InventoryApprovalRequestPort {
    private static final UUID SYSTEM_ACTOR_ID = new UUID(0, 0);
    private static final Duration VERIFICATION_TTL = Duration.ofMinutes(5);

    private final InventoryApprovalRepository repository;
    private final InventoryApprovalMessagePort approvedMessages;
    private final Clock clock;

    public InventoryApprovalService(InventoryApprovalRepository repository,
                                    InventoryApprovalMessagePort approvedMessages, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.approvedMessages = Objects.requireNonNull(approvedMessages);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public InventoryApproval request(UUID tenantId, UUID conversationId, String model, Integer year,
                                     String trim, String idempotencyKey) {
        var now = clock.instant();
        var request = InventoryApproval.pending(UUID.randomUUID(), tenantId, conversationId,
                model, year, trim, idempotencyKey, now);
        var saved = repository.savePending(request);
        if (saved.id().equals(request.id())) {
            repository.recordAudit(tenantId, saved.id(), SYSTEM_ACTOR_ID, "REQUESTED", now, saved.version());
        }
        return saved;
    }

    @Override
    public void requestInventory(UUID tenantId, UUID conversationId, String model, Integer year,
                                 String trim, String idempotencyKey) {
        request(tenantId, conversationId, model, year, trim, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public List<InventoryApproval> list(AuthenticatedOperator operator, UUID tenantId,
                                       InventoryApprovalStatus status, int limit) {
        requireTenant(operator, tenantId, false);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return repository.list(tenantId, status, limit);
    }

    @Transactional(readOnly = true)
    public InventoryApproval get(AuthenticatedOperator operator, UUID tenantId, UUID approvalId) {
        requireTenant(operator, tenantId, false);
        return requireApproval(tenantId, approvalId);
    }

    @Transactional
    public InventoryApproval verify(AuthenticatedOperator operator, UUID tenantId, UUID approvalId,
                                    long expectedVersion, InventoryOutcome outcome, Integer verifiedCount) {
        requireTenant(operator, tenantId, true);
        var approval = requireApproval(tenantId, approvalId);
        requireVersion(approval, expectedVersion);
        var now = clock.instant();
        var replacement = approval.verify(operatorId(operator), outcome, verifiedCount,
                now, now.plus(VERIFICATION_TTL), draft(approval, outcome, verifiedCount));
        var updated = transition(tenantId, approvalId, approval, replacement);
        repository.recordAudit(tenantId, approvalId, operatorId(operator), "VERIFIED", now, updated.version());
        return updated;
    }

    /** Returns EXPIRED without sending if verification is stale; otherwise commits approval and outbox together. */
    @Transactional
    public InventoryApproval approve(AuthenticatedOperator operator, UUID tenantId, UUID approvalId,
                                     long expectedApprovalVersion, long expectedConversationVersion,
                                     String exactText) {
        requireTenant(operator, tenantId, true);
        var approval = requireApproval(tenantId, approvalId);
        requireVersion(approval, expectedApprovalVersion);
        if (approval.status() != InventoryApprovalStatus.DRAFT_READY) {
            throw new IllegalStateException("inventory approval is not ready for approval");
        }
        var now = clock.instant();
        if (!now.isBefore(approval.verificationExpiresAt())) {
            var expired = approval.expire(now);
            var updated = transition(tenantId, approvalId, approval, expired);
            repository.recordAudit(tenantId, approvalId, operatorId(operator), "EXPIRED", now, updated.version());
            return updated;
        }

        var approved = approval.approve(operatorId(operator), exactText, now);
        var updated = transition(tenantId, approvalId, approval, approved);
        if (!approvedMessages.sendApprovedMessage(operator, tenantId, approval.conversationId(),
                expectedConversationVersion, updated.idempotencyKey(), updated.approvedText())) {
            throw new IllegalStateException("approved inventory message was not queued");
        }
        repository.recordAudit(tenantId, approvalId, operatorId(operator), "APPROVED", now, updated.version());
        return updated;
    }

    @Transactional
    public InventoryApproval reject(AuthenticatedOperator operator, UUID tenantId, UUID approvalId,
                                    long expectedVersion) {
        requireTenant(operator, tenantId, true);
        var approval = requireApproval(tenantId, approvalId);
        requireVersion(approval, expectedVersion);
        var now = clock.instant();
        var replacement = approval.reject(operatorId(operator), now);
        var updated = transition(tenantId, approvalId, approval, replacement);
        repository.recordAudit(tenantId, approvalId, operatorId(operator), "REJECTED", now, updated.version());
        return updated;
    }

    private InventoryApproval transition(UUID tenantId, UUID approvalId, InventoryApproval current,
                                         InventoryApproval replacement) {
        return repository.transition(tenantId, approvalId, current.version(), current.status(), replacement)
                .orElseThrow(() -> new IllegalStateException("inventory approval changed; reload before retrying"));
    }

    private InventoryApproval requireApproval(UUID tenantId, UUID approvalId) {
        return repository.find(tenantId, approvalId)
                .orElseThrow(() -> new NoSuchElementException("inventory approval not found"));
    }

    private static void requireVersion(InventoryApproval approval, long expectedVersion) {
        if (approval.version() != expectedVersion) {
            throw new IllegalStateException("inventory approval version is stale");
        }
    }

    private static void requireTenant(AuthenticatedOperator operator, UUID tenantId, boolean write) {
        if (operator == null || tenantId == null || !operator.canAccess(tenantId)) {
            throw new SecurityException("operator is not authorized for tenant");
        }
        if (write && !operator.hasAuthority("SCOPE_operator:write") && !operator.hasRole("ADMIN")) {
            throw new SecurityException("operator is not authorized to change inventory approval");
        }
        if (!write && !operator.hasAuthority("SCOPE_operator:read")
                && !operator.hasAuthority("SCOPE_operator:write") && !operator.hasRole("ADMIN")) {
            throw new SecurityException("operator is not authorized to view inventory approval");
        }
    }

    private static UUID operatorId(AuthenticatedOperator operator) {
        try {
            return UUID.fromString(operator.subject());
        } catch (IllegalArgumentException notUuid) {
            return UUID.nameUUIDFromBytes(operator.subject().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String draft(InventoryApproval approval, InventoryOutcome outcome, Integer count) {
        var vehicle = approval.model() + (approval.year() == null ? "" : " " + approval.year())
                + (approval.trim() == null ? "" : " " + approval.trim());
        return switch (outcome) {
            case AVAILABLE -> count == null
                    ? "El asesor confirmó disponibilidad de " + vehicle + ". ¿Desea conocer el siguiente paso?"
                    : "El asesor confirmó " + count + " unidades de " + vehicle + ". ¿Desea conocer el siguiente paso?";
            case UNAVAILABLE -> "El asesor confirmó que " + vehicle
                    + " no tiene disponibilidad por ahora. ¿Desea revisar otra versión?";
            case NEEDS_FOLLOW_UP -> "Un asesor verificará " + vehicle + " y le confirmará.";
        };
    }
}
