package com.ford.service.conversations;

import com.ford.service.conversations.api.MessageApprovalActionRequest;
import com.ford.service.conversations.api.MessageApprovalActionResult;
import com.ford.service.conversations.application.MessageApprovalService;
import com.ford.service.conversations.application.port.out.MessageApprovalRepository;
import com.ford.service.conversations.domain.AdvisorResponseDisposition;
import com.ford.service.conversations.domain.MessageApproval;
import com.ford.service.conversations.domain.MessageApproval.Action;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalQwenSyntheticOperatorApprovalTest {
    private static final Instant NOW = Instant.parse("2026-10-29T17:00:00Z");
    private final UUID tenant = UUID.randomUUID();
    private final UUID conversation = UUID.randomUUID();
    private final UUID receipt = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();
    private final UUID outboundId = UUID.randomUUID();
    private final String runId = "diagnostic-run-32";
    private final MessageApprovalRepository repository = mock(MessageApprovalRepository.class);
    private final MessageApprovalService approvalService = mock(MessageApprovalService.class);
    private final LocalQwenSyntheticOperatorApproval coordinator =
            new LocalQwenSyntheticOperatorApproval(repository, approvalService);

    @Test
    void approvesOnlyOnePendingExactReceiptDraftThroughApprovalService() {
        var approval = pending(receipt, true, "Draft exacto");
        when(repository.list(tenant, conversation)).thenReturn(List.of(approval));
        when(approvalService.act(any(AuthenticatedOperator.class), eq(tenant), eq(conversation), eq(approvalId),
                eq(approval.conversationVersion()), any(String.class), any(MessageApprovalActionRequest.class)))
                .thenReturn(new MessageApprovalActionResult(approvalId, "QUEUED", 8, outboundId));

        var result = coordinator.approveIfEligible(tenant, conversation, receipt, runId);

        assertThat(result.status()).isEqualTo(LocalQwenSyntheticOperatorApproval.Status.APPROVED);
        assertThat(result.approvedDraft()).isNotNull();
        assertThat(result.approvedDraft().draftText()).isEqualTo("Draft exacto");
        assertThat(result.approvedDraft().approvalId()).isEqualTo(approvalId);
        assertThat(result.approvedDraft().outboundMessageId()).isEqualTo(outboundId);
        verify(approvalService).act(argThat(operator -> operator.canAccess(tenant)
                        && operator.authorities().equals(Set.of("SCOPE_operator:write"))
                        && operator.roles().isEmpty()), eq(tenant), eq(conversation), eq(approvalId),
                eq(approval.conversationVersion()), eq("synthetic-operator:" + runId + ":" + receipt),
                argThat(request -> request.action() == Action.APPROVE
                        && request.expectedApprovalVersion() == approval.version()
                        && request.exactText().equals("Draft exacto")));
    }

    @Test
    void refusesToApproveWhenNoPendingApprovalMatchesTheSourceReceipt() {
        when(repository.list(tenant, conversation)).thenReturn(List.of(pending(UUID.randomUUID(), true, "Draft")));

        var result = coordinator.approveIfEligible(tenant, conversation, receipt, runId);

        assertThat(result.status()).isEqualTo(LocalQwenSyntheticOperatorApproval.Status.NO_PENDING_APPROVAL);
        assertThat(result.approvedDraft()).isNull();
        verifyNoInteractions(approvalService);
    }

    @Test
    void refusesToChooseWhenMultipleApprovalsMatchTheSourceReceipt() {
        when(repository.list(tenant, conversation)).thenReturn(List.of(
                pending(receipt, true, "Draft one"),
                pending(receipt, true, "Draft two")));

        var result = coordinator.approveIfEligible(tenant, conversation, receipt, runId);

        assertThat(result.status()).isEqualTo(LocalQwenSyntheticOperatorApproval.Status.MULTIPLE_PENDING_APPROVALS);
        verifyNoInteractions(approvalService);
    }

    @Test
    void ignoresACompletedApprovalForTheSameReceipt() {
        var pending = pending(receipt, true, "Draft exacto");
        var completed = pending.complete(pending.prepare(Action.APPROVE, pending.version(),
                        pending.conversationVersion(), pending.draftText()), UUID.randomUUID(),
                pending.conversationVersion() + 1, outboundId, NOW);
        when(repository.list(tenant, conversation)).thenReturn(List.of(completed));

        var result = coordinator.approveIfEligible(tenant, conversation, receipt, runId);

        assertThat(result.status()).isEqualTo(LocalQwenSyntheticOperatorApproval.Status.NO_PENDING_APPROVAL);
        verifyNoInteractions(approvalService);
    }

    @Test
    void refusesPendingDraftWithoutExplicitApprovePermission() {
        var notApprovable = pending(receipt, false, "Draft exacto");
        when(repository.list(tenant, conversation)).thenReturn(List.of(notApprovable));

        var result = coordinator.approveIfEligible(tenant, conversation, receipt, runId);

        assertThat(result.status()).isEqualTo(LocalQwenSyntheticOperatorApproval.Status.DRAFT_NOT_SENDABLE);
        verifyNoInteractions(approvalService);
    }

    @Test
    void classifiesApprovalEligibilityWithFixedReasonsAndStablePrecedence() {
        assertThat(LocalQwenSyntheticOperatorApproval.eligibilityFailure(false, true, "Draft"))
                .isEqualTo(LocalQwenSyntheticOperatorApproval.Status.DRAFT_NOT_SENDABLE);
        assertThat(LocalQwenSyntheticOperatorApproval.eligibilityFailure(true, false, "Draft"))
                .isEqualTo(LocalQwenSyntheticOperatorApproval.Status.APPROVE_ACTION_NOT_ALLOWED);
        assertThat(LocalQwenSyntheticOperatorApproval.eligibilityFailure(true, true, " "))
                .isEqualTo(LocalQwenSyntheticOperatorApproval.Status.DRAFT_MISSING);
        assertThat(LocalQwenSyntheticOperatorApproval.eligibilityFailure(true, true, "Draft")).isNull();
    }

    @Test
    void doesNotReportApprovalWhenServiceDoesNotReturnQueuedReceipt() {
        var approval = pending(receipt, true, "Draft exacto");
        when(repository.list(tenant, conversation)).thenReturn(List.of(approval));
        when(approvalService.act(any(AuthenticatedOperator.class), eq(tenant), eq(conversation), eq(approvalId),
                eq(approval.conversationVersion()), any(String.class), any(MessageApprovalActionRequest.class)))
                .thenReturn(new MessageApprovalActionResult(approvalId, "REJECTED", 7, null));

        var result = coordinator.approveIfEligible(tenant, conversation, receipt, runId);

        assertThat(result.status()).isEqualTo(LocalQwenSyntheticOperatorApproval.Status.APPROVAL_NOT_QUEUED);
        assertThat(result.approvedDraft()).isNull();
    }

    @Test
    void hidesApprovalServiceFailureDetails() {
        var approval = pending(receipt, true, "Draft exacto");
        when(repository.list(tenant, conversation)).thenReturn(List.of(approval));
        when(approvalService.act(any(AuthenticatedOperator.class), eq(tenant), eq(conversation), eq(approvalId),
                eq(approval.conversationVersion()), any(String.class), any(MessageApprovalActionRequest.class)))
                .thenThrow(new IllegalStateException("SYNTHETIC_SECRET_SENTINEL"));

        var result = coordinator.approveIfEligible(tenant, conversation, receipt, runId);

        assertThat(result.status()).isEqualTo(LocalQwenSyntheticOperatorApproval.Status.APPROVAL_FAILED);
        assertThat(result.toString()).doesNotContain("SYNTHETIC_SECRET_SENTINEL");
        assertThat(result.approvedDraft()).isNull();
    }

    @Test
    void hidesApprovalLookupFailureDetails() {
        when(repository.list(tenant, conversation)).thenThrow(new IllegalStateException("LOOKUP_SECRET_SENTINEL"));

        var result = coordinator.approveIfEligible(tenant, conversation, receipt, runId);

        assertThat(result.status()).isEqualTo(LocalQwenSyntheticOperatorApproval.Status.APPROVAL_FAILED);
        assertThat(result.toString()).doesNotContain("LOOKUP_SECRET_SENTINEL");
        verifyNoInteractions(approvalService);
    }

    private MessageApproval pending(UUID sourceReceipt, boolean draftMayBeApproved, String draft) {
        return MessageApproval.pending(approvalId, tenant, conversation, sourceReceipt, 7,
                new AdvisorResponseDisposition(AdvisorResponseDisposition.Route.REVIEW,
                        AdvisorResponseDisposition.Reason.RESPONSE_REVIEW), draft, draftMayBeApproved, NOW);
    }
}
