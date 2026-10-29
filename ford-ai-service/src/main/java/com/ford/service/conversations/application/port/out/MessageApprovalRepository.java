package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.domain.MessageApproval;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All mutations join the caller transaction; lock conversation before approval. */
public interface MessageApprovalRepository {
    Optional<ConversationTarget> lockConversation(UUID tenantId, UUID conversationId);
    Optional<MessageApproval> findForUpdate(UUID tenantId, UUID conversationId, UUID approvalId);
    MessageApproval savePending(MessageApproval approval);
    List<MessageApproval> list(UUID tenantId, UUID conversationId);
    boolean replace(MessageApproval current, MessageApproval replacement);
    void recordAudit(UUID tenantId, UUID approvalId, UUID actorId, AuditRecord audit, Instant at);

    record ConversationTarget(long version, String state, UUID latestReceiptId) { }
    record AuditRecord(String action, long approvalVersion, UUID sourceReceiptId, UUID outboundMessageId) { }
}
