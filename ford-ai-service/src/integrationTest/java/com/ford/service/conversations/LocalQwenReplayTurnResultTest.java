package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class LocalQwenReplayTurnResultTest {
    @Test
    void representsCommittedQwenReplyAsCommitted() {
        var conversationId = UUID.randomUUID();
        var outboundMessageId = UUID.randomUUID();

        var result = LocalQwenReplayTurnResult.committed(conversationId, "Respuesta Qwen.", outboundMessageId);

        assertThat(result.conversationId()).isEqualTo(conversationId);
        assertThat(result.answer()).isEqualTo("Respuesta Qwen.");
        assertThat(result.replyOrigin()).isEqualTo(LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN);
        assertThat(result.persistedTurnState()).isEqualTo("REPLY_COMMITTED");
        assertThat(result.approvalId()).isNull();
        assertThat(result.outboundMessageId()).isEqualTo(outboundMessageId);
    }

    @Test
    void representsApprovedDraftWithoutChangingNeedsOperatorState() {
        var conversationId = UUID.randomUUID();
        var approvalId = UUID.randomUUID();
        var outboundMessageId = UUID.randomUUID();
        var draft = new LocalQwenSyntheticOperatorApproval.ApprovedDraft(
                approvalId, "Approved draft.", outboundMessageId);

        var result = LocalQwenReplayTurnResult.approvedDraft(conversationId, draft);

        assertThat(result.conversationId()).isEqualTo(conversationId);
        assertThat(result.answer()).isEqualTo("Approved draft.");
        assertThat(result.replyOrigin()).isEqualTo(
                LocalQwenTranscriptWriter.ReplyOrigin.SYNTHETIC_OPERATOR_APPROVED);
        assertThat(result.persistedTurnState()).isEqualTo("NEEDS_OPERATOR");
        assertThat(result.approvalId()).isEqualTo(approvalId);
        assertThat(result.outboundMessageId()).isEqualTo(outboundMessageId);
    }

    @Test
    void refusesMissingConversationOrApprovalReceipt() {
        assertThatNullPointerException().isThrownBy(() ->
                LocalQwenReplayTurnResult.committed(null, "Answer", UUID.randomUUID()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                LocalQwenReplayTurnResult.committed(UUID.randomUUID(), " ", UUID.randomUUID()));
        assertThatNullPointerException().isThrownBy(() ->
                LocalQwenReplayTurnResult.committed(UUID.randomUUID(), "Answer", null));
        assertThatNullPointerException().isThrownBy(() ->
                LocalQwenReplayTurnResult.approvedDraft(UUID.randomUUID(), null));
    }
}
