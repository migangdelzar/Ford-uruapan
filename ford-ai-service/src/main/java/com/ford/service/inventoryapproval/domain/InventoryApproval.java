package com.ford.service.inventoryapproval.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable approval state. Does not store customer prompts or catalog/tool payloads. */
public record InventoryApproval(UUID id, UUID tenantId, UUID conversationId, String model, Integer year,
                                String trim, InventoryApprovalStatus status, InventoryOutcome outcome,
                                Integer verifiedCount, Instant verifiedAt, Instant verificationExpiresAt,
                                String draftText, String approvedText, UUID verifiedBy, UUID approvedBy,
                                String idempotencyKey, long version, UUID rejectedBy, Instant createdAt) {

    public InventoryApproval {
        Objects.requireNonNull(id, "approval ID is required");
        Objects.requireNonNull(tenantId, "tenant ID is required");
        Objects.requireNonNull(conversationId, "conversation ID is required");
        model = requiredText(model, "model", 100);
        if (year != null && year < 1900) throw new IllegalArgumentException("model year is invalid");
        if (trim != null && trim.isBlank()) throw new IllegalArgumentException("trim must be absent or nonblank");
        Objects.requireNonNull(status, "approval status is required");
        Objects.requireNonNull(createdAt, "creation timestamp is required");
        idempotencyKey = requiredText(idempotencyKey, "idempotency key", 240);
        if (version < 0) throw new IllegalArgumentException("approval version cannot be negative");

        if (status == InventoryApprovalStatus.PENDING) {
            if (outcome != null || verifiedAt != null || verificationExpiresAt != null || draftText != null
                    || approvedText != null || verifiedBy != null || approvedBy != null || verifiedCount != null) {
                throw new IllegalArgumentException("pending approval cannot contain verification or approval data");
            }
        } else if (status == InventoryApprovalStatus.DRAFT_READY || status == InventoryApprovalStatus.APPROVED
                || status == InventoryApprovalStatus.EXPIRED) {
            Objects.requireNonNull(outcome, "verification outcome is required");
            Objects.requireNonNull(verifiedAt, "verification timestamp is required");
            Objects.requireNonNull(verificationExpiresAt, "verification expiry is required");
            Objects.requireNonNull(verifiedBy, "verifier is required");
            if (!verificationExpiresAt.isAfter(verifiedAt)) {
                throw new IllegalArgumentException("verification expiry must follow verification time");
            }
            draftText = requiredText(draftText, "draft text", 1000);
            validateCount(outcome, verifiedCount);
            if (status == InventoryApprovalStatus.APPROVED) {
                approvedText = requiredText(approvedText, "approved text", 1000);
                Objects.requireNonNull(approvedBy, "approver is required");
            }
        }
        if (status == InventoryApprovalStatus.REJECTED) {
            Objects.requireNonNull(rejectedBy, "rejector is required");
        }
    }

    public static InventoryApproval pending(UUID id, UUID tenantId, UUID conversationId,
                                            String model, Integer year, String trim,
                                            String idempotencyKey, Instant createdAt) {
        Objects.requireNonNull(createdAt, "creation timestamp is required");
        return new InventoryApproval(id, tenantId, conversationId, model, year, trim,
                InventoryApprovalStatus.PENDING, null, null, null, null, null, null, null,
                null, idempotencyKey, 0, null, createdAt);
    }

    public InventoryApproval verify(UUID advisorId, InventoryOutcome selectedOutcome, Integer count,
                                    Instant verifiedAt, Instant expiresAt, String draft) {
        requireStatus(InventoryApprovalStatus.PENDING);
        Objects.requireNonNull(advisorId, "verifier ID is required");
        Objects.requireNonNull(selectedOutcome, "inventory outcome is required");
        Objects.requireNonNull(verifiedAt, "verification timestamp is required");
        Objects.requireNonNull(expiresAt, "verification expiry is required");
        if (!expiresAt.isAfter(verifiedAt)) throw new IllegalArgumentException("verification expiry must follow verification time");
        validateCount(selectedOutcome, count);
        return new InventoryApproval(id, tenantId, conversationId, model, year, trim,
                InventoryApprovalStatus.DRAFT_READY, selectedOutcome, count, verifiedAt, expiresAt,
                requiredText(draft, "draft text", 1000), null, advisorId, null,
                idempotencyKey, version + 1, null, createdAt);
    }

    public InventoryApproval approve(UUID advisorId, String exactText, Instant now) {
        requireStatus(InventoryApprovalStatus.DRAFT_READY);
        Objects.requireNonNull(advisorId, "approver ID is required");
        Objects.requireNonNull(now, "approval timestamp is required");
        if (!now.isBefore(verificationExpiresAt)) {
            throw new IllegalStateException("inventory verification has expired");
        }
        var approved = requiredText(exactText, "approved text", 1000);
        if (!draftText.equals(approved)) {
            throw new IllegalArgumentException("approved text must exactly match the verified draft");
        }
        return new InventoryApproval(id, tenantId, conversationId, model, year, trim,
                InventoryApprovalStatus.APPROVED, outcome, verifiedCount, verifiedAt, verificationExpiresAt,
                draftText, approved, verifiedBy, advisorId,
                "inventory-approval:" + id + ":" + version, version + 1, null, createdAt);
    }

    public InventoryApproval reject(UUID advisorId, Instant now) {
        if (status != InventoryApprovalStatus.PENDING && status != InventoryApprovalStatus.DRAFT_READY) {
            throw new IllegalStateException("approval cannot be rejected from " + status);
        }
        Objects.requireNonNull(advisorId, "rejector ID is required");
        Objects.requireNonNull(now, "rejection timestamp is required");
        return new InventoryApproval(id, tenantId, conversationId, model, year, trim,
                InventoryApprovalStatus.REJECTED, outcome, verifiedCount, verifiedAt, verificationExpiresAt,
                draftText, null, verifiedBy, null, idempotencyKey, version + 1, advisorId, createdAt);
    }

    public InventoryApproval expire(Instant now) {
        if (status != InventoryApprovalStatus.DRAFT_READY) {
            throw new IllegalStateException("only a draft can expire");
        }
        Objects.requireNonNull(now, "expiration timestamp is required");
        if (now.isBefore(verificationExpiresAt)) throw new IllegalStateException("verification is still current");
        return new InventoryApproval(id, tenantId, conversationId, model, year, trim,
                InventoryApprovalStatus.EXPIRED, outcome, verifiedCount, verifiedAt, verificationExpiresAt,
                draftText, null, verifiedBy, null, idempotencyKey, version + 1, null, createdAt);
    }

    private void requireStatus(InventoryApprovalStatus expected) {
        if (status != expected) throw new IllegalStateException("approval must be " + expected + ", not " + status);
    }

    private static void validateCount(InventoryOutcome outcome, Integer count) {
        if (count != null && (outcome != InventoryOutcome.AVAILABLE || count < 0)) {
            throw new IllegalArgumentException("a nonnegative count is valid only for available inventory");
        }
    }

    private static String requiredText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be nonblank and at most " + maxLength + " characters");
        }
        return value.strip();
    }
}
