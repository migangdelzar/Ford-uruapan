package com.ford.service.conversations;

import com.ford.service.conversations.domain.AdvisorResponseDisposition;
import com.ford.service.conversations.domain.AdvisorResponseDisposition.Reason;
import com.ford.service.conversations.domain.AdvisorResponseDisposition.Route;
import com.ford.service.conversations.domain.MessageApproval;
import com.ford.service.conversations.domain.MessageApproval.Action;
import com.ford.service.conversations.domain.MessageApproval.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class MessageApprovalTest {
    private static final Instant NOW = Instant.parse("2026-10-28T16:30:00Z");
    private static final UUID TENANT = UUID.randomUUID(), CONVERSATION = UUID.randomUUID();
    private static final UUID RECEIPT = UUID.randomUUID(), ACTOR = UUID.randomUUID();

    @Test
    void bindsApprovalToExactSendableDraftBothVersionsAndAnActualQueuedReceipt() {
        var approval = pending("  Texto exacto.\n", true);
        var command = approval.prepare(Action.APPROVE, 0, 7, "  Texto exacto.\n");
        var message = UUID.randomUUID();
        var queued = approval.complete(command, ACTOR, 8, message, NOW.plusSeconds(1));
        assertThat(queued.status()).isEqualTo(Status.QUEUED);
        assertThat(queued.version()).isEqualTo(1);
        assertThat(queued.conversationVersion()).isEqualTo(8);
        assertThat(queued.outboundMessageId()).isEqualTo(message);
        assertThat(queued.reviewedBy()).isEqualTo(ACTOR);
        assertThat(queued.draftText()).isEqualTo("  Texto exacto.\n");
        assertThat(queued.allowedActions()).isEmpty();
        assertThat(approval.status()).isEqualTo(Status.PENDING);
        assertThat(approval.toString()).doesNotContain("Texto exacto");
        assertThat(command.toString()).doesNotContain("Texto exacto");
    }

    @Test
    void cannotApproveMissingBlockedOrChangedDraftButMayPrepareAnAuthoredReplacement() {
        var missing = pending(null, false);
        assertThat(missing.allowedActions()).doesNotContain(Action.APPROVE).contains(Action.EDIT);
        assertThatIllegalStateException().isThrownBy(() -> missing.prepare(Action.APPROVE, 0, 7, "Inventado"));
        assertThat(missing.prepare(Action.EDIT, 0, 7, "Respuesta redactada por asesor").exactText())
                .isEqualTo("Respuesta redactada por asesor");
        assertThatIllegalStateException().isThrownBy(() -> pending("Bloqueado", false).prepare(Action.APPROVE, 0, 7, "Bloqueado"));
        assertThatIllegalArgumentException().isThrownBy(() -> pending("Original", true).prepare(Action.APPROVE, 0, 7, "Cambiado"));
    }

    @Test
    void rejectsStaleVersionsEmptyOrOverlongTextAndTextOnNonSendingActions() {
        var approval = pending("Original", true);
        assertThatIllegalStateException().isThrownBy(() -> approval.prepare(Action.APPROVE, 1, 7, "Original"));
        assertThatIllegalStateException().isThrownBy(() -> approval.prepare(Action.APPROVE, 0, 8, "Original"));
        assertThatIllegalArgumentException().isThrownBy(() -> approval.prepare(Action.EDIT, 0, 7, " "));
        assertThatIllegalArgumentException().isThrownBy(() -> approval.prepare(Action.EDIT, 0, 7, "x".repeat(2001)));
        assertThatIllegalArgumentException().isThrownBy(() -> approval.prepare(Action.REJECT, 0, 7, "No enviar"));
    }

    @Test
    void mandatoryHandoffOnlyAllowsTakeoverAndCannotStoreAnUnsafeDraft() {
        var disposition = new AdvisorResponseDisposition(Route.MANDATORY_HANDOFF, Reason.SENSITIVE_DOCUMENT);
        var approval = MessageApproval.pending(UUID.randomUUID(), TENANT, CONVERSATION, RECEIPT, 7,
                disposition, null, false, NOW);
        assertThat(approval.allowedActions()).containsExactly(Action.TAKEOVER);
        assertThatIllegalStateException().isThrownBy(() -> approval.prepare(Action.EDIT, 0, 7, "Bypass"));
        assertThatIllegalArgumentException().isThrownBy(() -> MessageApproval.pending(UUID.randomUUID(), TENANT,
                CONVERSATION, RECEIPT, 7, disposition, "Sensitive document text", false, NOW));
        var taken = approval.complete(approval.prepare(Action.TAKEOVER, 0, 7, null), ACTOR, 9, null, NOW);
        assertThat(taken.status()).isEqualTo(Status.TAKEN_OVER);
    }

    @Test
    void rejectionKeepsBotHeldAndStillAllowsAdvisorTakeover() {
        var approval = pending("Original", true);
        var rejected = approval.complete(approval.prepare(Action.REJECT, 0, 7, null), ACTOR, 7, null, NOW);
        assertThat(rejected.status()).isEqualTo(Status.REJECTED);
        assertThat(rejected.blocksAutomatedReplies()).isTrue();
        assertThat(rejected.allowedActions()).containsExactly(Action.TAKEOVER);
        assertThatIllegalStateException().isThrownBy(() -> rejected.prepare(Action.APPROVE, 1, 7, "Original"));
        assertThat(rejected.complete(rejected.prepare(Action.TAKEOVER, 1, 7, null), ACTOR, 8, null, NOW).status())
                .isEqualTo(Status.TAKEN_OVER);
    }

    @Test
    void cannotQueueWithoutMessageReceiptOrVersionAdvanceOrUseAnotherDraftsCommand() {
        var approval = pending("Original", true);
        var command = approval.prepare(Action.APPROVE, 0, 7, "Original");
        assertThatIllegalArgumentException().isThrownBy(() -> approval.complete(command, ACTOR, 8, null, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> approval.complete(command, ACTOR, 7, UUID.randomUUID(), NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> pending("Original", true)
                .complete(command, ACTOR, 8, UUID.randomUUID(), NOW));
    }

    @Test
    void newConversationVersionSupersedesTheOldCandidate() {
        var superseded = pending("Original", true).supersede(8, NOW.plusSeconds(1));
        assertThat(superseded.status()).isEqualTo(Status.SUPERSEDED);
        assertThat(superseded.allowedActions()).isEmpty();
        assertThatIllegalStateException().isThrownBy(() -> superseded.prepare(Action.APPROVE, 1, 8, "Original"));
    }

    @Test
    void rejectsAutomaticApprovalsInconsistentReasonsAndTimeTravel() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AdvisorResponseDisposition(Route.REVIEW, Reason.HUMAN_REQUEST));
        assertThatIllegalArgumentException().isThrownBy(() -> MessageApproval.pending(UUID.randomUUID(), TENANT,
                CONVERSATION, RECEIPT, 7, new AdvisorResponseDisposition(Route.AUTOMATIC, Reason.VERIFIED_ROUTINE), null, false, NOW));
        var approval = pending("Original", true);
        assertThatIllegalArgumentException().isThrownBy(() -> approval.complete(
                approval.prepare(Action.REJECT, 0, 7, null), ACTOR, 7, null, NOW.minusSeconds(1)));
    }

    private static MessageApproval pending(String text, boolean sendable) {
        return MessageApproval.pending(UUID.randomUUID(), TENANT, CONVERSATION, RECEIPT, 7,
                new AdvisorResponseDisposition(Route.REVIEW, Reason.RESPONSE_REVIEW), text, sendable, NOW);
    }
}
