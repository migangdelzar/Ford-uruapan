package com.ford.service.conversations.adapter.out;

import com.ford.service.conversations.application.ConversationTurnState;
import com.ford.service.conversations.application.port.out.ConversationTurnLedgerPort;
import com.ford.service.conversations.application.port.out.ConversationReviewPort;
import com.ford.service.conversations.application.ConversationReviewService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Repository
public class JdbcConversationTurnLedgerAdapter implements ConversationTurnLedgerPort, ConversationReviewPort {
    private final JdbcClient jdbc;

    public JdbcConversationTurnLedgerAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void recordOutcome(UUID tenantId, UUID conversationId, UUID receiptId, String providerMessageId,
                              ConversationTurnState state, String failureCategory, Instant occurredAt) {
        var updated = jdbc.sql("""
                update conversation_turn
                   set state = :state, failure_category = :failureCategory,
                       updated_at = :occurredAt,
                       next_attempt_at = :nextAttemptAt,
                       attempt_count = attempt_count + :attemptIncrement,
                       version = version + 1
                 where tenant_id = :tenantId and conversation_id = :conversationId and receipt_id = :receiptId
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("receiptId", receiptId)
                .param("state", state.name())
                .param("failureCategory", failureCategory)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("nextAttemptAt", state == ConversationTurnState.RETRY_WAIT
                        ? Timestamp.from(occurredAt) : null)
                .param("attemptIncrement", state == ConversationTurnState.PROCESSING ? 1 : 0)
                .update();
        if (updated == 0) {
            jdbc.sql("""
                    insert into conversation_turn
                        (tenant_id, conversation_id, receipt_id, provider_message_id, state,
                         attempt_count, failure_category, accepted_at, updated_at, next_attempt_at, version)
                    values (:tenantId, :conversationId, :receiptId, :providerMessageId, :state,
                            :attemptIncrement, :failureCategory, :occurredAt, :occurredAt,
                            :nextAttemptAt, 0)
                    """)
                    .param("tenantId", tenantId)
                    .param("conversationId", conversationId)
                    .param("receiptId", receiptId)
                    .param("providerMessageId", providerMessageId)
                    .param("state", state.name())
                    .param("failureCategory", failureCategory)
                    .param("occurredAt", Timestamp.from(occurredAt))
                    .param("nextAttemptAt", state == ConversationTurnState.RETRY_WAIT
                            ? Timestamp.from(occurredAt) : null)
                    .param("attemptIncrement", state == ConversationTurnState.PROCESSING ? 1 : 0)
                    .update();
        }
    }

    @Override
    public int consecutiveSubstantiveFailures(UUID tenantId, UUID conversationId) {
        var terminalOutcomes = jdbc.sql("""
                select case when ct.state='SAFE_FALLBACK_REVIEW' or a.reason_code='RESPONSE_REVIEW'
                            then 'SAFE_FALLBACK_REVIEW' else 'REPLY_COMMITTED' end as substantive_state
                  from conversation_turn ct
                  left join message_approval a
                    on a.tenant_id=ct.tenant_id and a.conversation_id=ct.conversation_id
                   and a.source_receipt_id=ct.receipt_id and a.disposition_route='REVIEW'
                 where ct.tenant_id=:tenantId and ct.conversation_id=:conversationId
                   and (ct.state in ('SAFE_FALLBACK_REVIEW', 'REPLY_COMMITTED')
                        or (ct.state='NEEDS_OPERATOR' and a.reason_code='RESPONSE_REVIEW'))
                 order by ct.accepted_at desc, ct.receipt_id desc
                 limit 100
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query((rs, rowNum) -> rs.getString("substantive_state")).list();
        var failures = 0;
        for (var state : terminalOutcomes) {
            if (!ConversationTurnState.SAFE_FALLBACK_REVIEW.name().equals(state)) break;
            failures++;
        }
        return failures;
    }

    @Override
    public void recordReview(UUID tenantId, UUID conversationId, UUID receiptId, String reasonCode, Instant createdAt) {
        var existing = jdbc.sql("""
                select count(*) from conversation_review_item
                 where tenant_id = :tenantId and conversation_id = :conversationId and receipt_id = :receiptId
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("receiptId", receiptId)
                .query(Integer.class).single();
        if (existing > 0) return;
        try {
            jdbc.sql("""
                    insert into conversation_review_item
                        (id, tenant_id, conversation_id, receipt_id, reason_code, status, created_at, updated_at)
                    values (:id, :tenantId, :conversationId, :receiptId, :reasonCode, 'PENDING', :createdAt, :createdAt)
                    """)
                    .param("id", UUID.nameUUIDFromBytes((tenantId + ":" + conversationId + ":" + receiptId)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .param("tenantId", tenantId)
                    .param("conversationId", conversationId)
                    .param("receiptId", receiptId)
                    .param("reasonCode", reasonCode)
                    .param("createdAt", Timestamp.from(createdAt))
                    .update();
        } catch (DuplicateKeyException duplicate) {
            var concurrentInsertWon = jdbc.sql("""
                    select count(*) from conversation_review_item
                     where tenant_id = :tenantId and conversation_id = :conversationId and receipt_id = :receiptId
                    """)
                    .param("tenantId", tenantId)
                    .param("conversationId", conversationId)
                    .param("receiptId", receiptId)
                    .query(Integer.class).single() > 0;
            if (!concurrentInsertWon) throw duplicate;
        }
    }

    @Override
    public java.util.List<ConversationReviewService.ReviewItem> list(UUID tenantId, String status, int limit) {
        return jdbc.sql("""
                select id, tenant_id, conversation_id, receipt_id, reason_code, status, created_at, updated_at
                  from conversation_review_item
                 where tenant_id = :tenantId and status = :status
                 order by created_at, id
                 limit :limit
                """)
                .param("tenantId", tenantId)
                .param("status", status)
                .param("limit", limit)
                .query((rs, rowNum) -> new ConversationReviewService.ReviewItem(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class), rs.getObject("receipt_id", UUID.class),
                        rs.getString("reason_code"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    @Override
    public boolean resolve(UUID tenantId, UUID reviewId, UUID operatorId, Instant reviewedAt) {
        return jdbc.sql("""
                update conversation_review_item
                   set status = 'RESOLVED', reviewed_by = :operatorId, reviewed_at = :reviewedAt,
                       updated_at = :reviewedAt
                 where tenant_id = :tenantId and id = :reviewId and status in ('PENDING', 'IN_PROGRESS')
                """)
                .param("tenantId", tenantId)
                .param("reviewId", reviewId)
                .param("operatorId", operatorId)
                .param("reviewedAt", Timestamp.from(reviewedAt))
                .update() == 1;
    }
}
