package com.ford.service.conversations;

import java.util.Objects;
import java.util.UUID;

/** A recorded replay response, with approval distinct from the persisted assistant-commit state. */
record LocalQwenReplayTurnResult(UUID conversationId, String answer,
                                 LocalQwenTranscriptWriter.ReplyOrigin replyOrigin,
                                 String persistedTurnState, UUID approvalId, UUID outboundMessageId) {
    LocalQwenReplayTurnResult {
        Objects.requireNonNull(conversationId, "conversation ID is required");
        if (answer == null || answer.isBlank()) throw new IllegalArgumentException("replay answer is required");
        Objects.requireNonNull(replyOrigin, "reply origin is required");
        Objects.requireNonNull(outboundMessageId, "outbound receipt is required");
        if (LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN.equals(replyOrigin)) {
            if (!"REPLY_COMMITTED".equals(persistedTurnState) || approvalId != null || outboundMessageId == null) {
                throw new IllegalArgumentException("live Qwen result must be a committed reply without approval");
            }
        } else if (LocalQwenTranscriptWriter.ReplyOrigin.SYNTHETIC_OPERATOR_APPROVED.equals(replyOrigin)) {
            if (!"NEEDS_OPERATOR".equals(persistedTurnState) || approvalId == null || outboundMessageId == null) {
                throw new IllegalArgumentException("synthetic approval must preserve NEEDS_OPERATOR and receipt identity");
            }
        } else {
            throw new IllegalArgumentException("unsupported live replay reply origin");
        }
    }

    static LocalQwenReplayTurnResult committed(UUID conversationId, String answer, UUID outboundMessageId) {
        return new LocalQwenReplayTurnResult(conversationId, answer,
                LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN, "REPLY_COMMITTED", null, outboundMessageId);
    }

    static LocalQwenReplayTurnResult approvedDraft(UUID conversationId,
                                                    LocalQwenSyntheticOperatorApproval.ApprovedDraft approved) {
        Objects.requireNonNull(approved, "queued approval receipt is required");
        return new LocalQwenReplayTurnResult(conversationId, approved.draftText(),
                LocalQwenTranscriptWriter.ReplyOrigin.SYNTHETIC_OPERATOR_APPROVED,
                "NEEDS_OPERATOR", approved.approvalId(), approved.outboundMessageId());
    }
}
