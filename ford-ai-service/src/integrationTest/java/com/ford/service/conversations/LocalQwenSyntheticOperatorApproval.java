package com.ford.service.conversations;

import com.ford.service.conversations.api.MessageApprovalActionRequest;
import com.ford.service.conversations.application.MessageApprovalService;
import com.ford.service.conversations.application.port.out.MessageApprovalRepository;
import com.ford.service.conversations.domain.MessageApproval;
import com.ford.service.conversations.domain.MessageApproval.Action;
import com.ford.service.operator.application.AuthenticatedOperator;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Test-harness-only approval coordinator. Never creates a draft or bypasses MessageApprovalService. */
final class LocalQwenSyntheticOperatorApproval {
    private final MessageApprovalRepository approvals;
    private final MessageApprovalService approvalService;

    LocalQwenSyntheticOperatorApproval(MessageApprovalRepository approvals, MessageApprovalService approvalService) {
        this.approvals = Objects.requireNonNull(approvals);
        this.approvalService = Objects.requireNonNull(approvalService);
    }

    Result approveIfEligible(UUID tenantId, UUID conversationId, UUID sourceReceiptId, String runId) {
        if (tenantId == null || conversationId == null || sourceReceiptId == null
                || runId == null || !runId.matches("[A-Za-z0-9-]{1,80}")) {
            throw new IllegalArgumentException("synthetic approval identity is invalid");
        }
        var pending = approvals.list(tenantId, conversationId).stream()
                .filter(approval -> approval.sourceReceiptId().equals(sourceReceiptId))
                .filter(approval -> approval.status() == MessageApproval.Status.PENDING)
                .toList();
        if (pending.isEmpty()) return new Result(Status.NO_PENDING_APPROVAL, null);
        if (pending.size() != 1) return new Result(Status.MULTIPLE_PENDING_APPROVALS, null);
        var approval = pending.getFirst();
        if (!approval.draftMayBeApproved() || !approval.allowedActions().contains(Action.APPROVE)
                || approval.draftText() == null || approval.draftText().isBlank()) {
            return new Result(Status.NOT_APPROVABLE, null);
        }

        var operatorId = UUID.nameUUIDFromBytes(("synthetic-operator:" + runId).getBytes(StandardCharsets.UTF_8));
        var operator = new AuthenticatedOperator(operatorId.toString(), Set.of("SCOPE_operator:write"),
                Set.of(), Set.of(tenantId));
        var idempotencyKey = "synthetic-operator:" + runId + ":" + sourceReceiptId;
        try {
            var receipt = approvalService.act(operator, tenantId, conversationId, approval.id(),
                    approval.conversationVersion(), idempotencyKey,
                    new MessageApprovalActionRequest(Action.APPROVE, approval.version(), approval.draftText()));
            if (receipt == null || !"QUEUED".equals(receipt.status()) || receipt.messageId() == null) {
                return new Result(Status.APPROVAL_NOT_QUEUED, null);
            }
            return new Result(Status.APPROVED,
                    new ApprovedDraft(approval.id(), approval.draftText(), receipt.messageId()));
        } catch (RuntimeException failure) {
            return new Result(Status.APPROVAL_FAILED, null);
        }
    }

    enum Status {
        APPROVED, NO_PENDING_APPROVAL, MULTIPLE_PENDING_APPROVALS, NOT_APPROVABLE,
        APPROVAL_NOT_QUEUED, APPROVAL_FAILED
    }

    record Result(Status status, ApprovedDraft approvedDraft) {
        Result {
            Objects.requireNonNull(status);
            if ((status == Status.APPROVED) != (approvedDraft != null)) {
                throw new IllegalArgumentException("approved result must carry exactly one approval receipt");
            }
        }

        @Override public String toString() {
            return "Result[status=" + status + ", approvedDraft="
                    + (approvedDraft == null ? "null" : "[redacted]") + "]";
        }
    }

    record ApprovedDraft(UUID approvalId, String draftText, UUID outboundMessageId) {
        ApprovedDraft {
            Objects.requireNonNull(approvalId);
            Objects.requireNonNull(outboundMessageId);
            if (draftText == null || draftText.isBlank() || draftText.length() > 2000) {
                throw new IllegalArgumentException("approved draft text is invalid");
            }
        }

        @Override public String toString() {
            return "ApprovedDraft[approvalId=" + approvalId + ", outboundMessageId=" + outboundMessageId
                    + ", draftTextChars=" + draftText.length() + "]";
        }
    }
}
