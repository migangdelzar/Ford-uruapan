package com.ford.service.conversations.application;

import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.port.out.MessageApprovalRepository;
import com.ford.service.conversations.domain.AdvisorResponseDisposition;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.MessageApproval;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Persists the advisor hold in the same database transaction as the conversation version bump. */
@Service
public class MessageApprovalIntakeService {
    private final MessageApprovalRepository approvals;
    private final Clock clock;

    public MessageApprovalIntakeService(MessageApprovalRepository approvals, Clock clock) {
        this.approvals = Objects.requireNonNull(approvals);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public MessageApproval hold(ConversationTurnReference reference, ConversationSnapshot conversation,
                                AdvisorResponseDisposition disposition, String draftText,
                                boolean draftMayBeApproved) {
        Objects.requireNonNull(reference, "turn reference is required");
        Objects.requireNonNull(conversation, "conversation snapshot is required");
        Objects.requireNonNull(disposition, "advisor disposition is required");
        if (!reference.tenantId().equals(conversation.tenantId())
                || !reference.conversationId().equals(conversation.conversationId())) {
            throw new SecurityException("approval turn does not match conversation scope");
        }
        if (disposition.route() == AdvisorResponseDisposition.Route.AUTOMATIC) {
            throw new IllegalArgumentException("automatic responses do not create advisor holds");
        }
        return approvals.savePending(MessageApproval.pending(UUID.randomUUID(), reference.tenantId(),
                reference.conversationId(), reference.receiptId(), conversation.version(), disposition,
                draftText, draftMayBeApproved, clock.instant()));
    }
}
