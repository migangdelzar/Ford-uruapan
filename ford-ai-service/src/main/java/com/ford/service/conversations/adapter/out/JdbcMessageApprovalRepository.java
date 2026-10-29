package com.ford.service.conversations.adapter.out;

import com.ford.service.conversations.application.port.out.MessageApprovalRepository;
import com.ford.service.conversations.domain.AdvisorResponseDisposition;
import com.ford.service.conversations.domain.MessageApproval;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.ford.service.channels.application.port.out.AdvisorHoldInvalidationPort;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcMessageApprovalRepository implements MessageApprovalRepository, AdvisorHoldInvalidationPort {
    private final JdbcClient jdbc;

    public JdbcMessageApprovalRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public Optional<ConversationTarget> lockConversation(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                select c.version, c.state,
                       (select m.id from channel_message m
                         where m.tenant_id = c.tenant_id and m.conversation_id = c.id
                         order by m.received_at desc, m.id desc limit 1) as latest_receipt_id
                  from conversation c
                 where c.tenant_id = :tenantId and c.id = :conversationId
                 for update of c
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query((rs, row) -> new ConversationTarget(rs.getLong("version"), rs.getString("state"),
                        rs.getObject("latest_receipt_id", UUID.class))).optional();
    }

    @Override public Optional<MessageApproval> findForUpdate(UUID tenantId, UUID conversationId, UUID approvalId) {
        return jdbc.sql("""
                select * from message_approval
                 where tenant_id = :tenantId and conversation_id = :conversationId and id = :approvalId
                 for update
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId).param("approvalId", approvalId)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override public MessageApproval savePending(MessageApproval approval) {
        if (approval.status() != MessageApproval.Status.PENDING || approval.version() != 0) {
            throw new IllegalArgumentException("only a new pending approval can be inserted");
        }
        var current = lockConversation(approval.tenantId(), approval.conversationId())
                .orElseThrow(() -> new IllegalStateException("conversation no longer exists"));
        if (current.version() != approval.conversationVersion()
                || !approval.sourceReceiptId().equals(current.latestReceiptId())
                || !List.of("BOT_ACTIVE", "LEAD_CAPTURE").contains(current.state())) {
            throw new IllegalStateException("approval source is stale or conversation is not bot-active");
        }
        var heldConversationVersion = Math.addExact(current.version(), 1);
        var advanced = jdbc.sql("""
                update conversation set version=:heldVersion, updated_at=:updatedAt
                 where tenant_id=:tenantId and id=:conversationId and version=:expectedVersion
                   and state in ('BOT_ACTIVE', 'LEAD_CAPTURE')
                """)
                .param("heldVersion", heldConversationVersion).param("updatedAt", Timestamp.from(approval.createdAt()))
                .param("tenantId", approval.tenantId()).param("conversationId", approval.conversationId())
                .param("expectedVersion", current.version()).update();
        if (advanced != 1) throw new IllegalStateException("conversation changed while creating advisor hold");
        var persisted = new MessageApproval(approval.id(), approval.tenantId(), approval.conversationId(),
                approval.sourceReceiptId(), approval.version(), heldConversationVersion, approval.disposition(),
                approval.status(), approval.draftText(), approval.draftMayBeApproved(), approval.createdAt(),
                approval.updatedAt(), approval.reviewedBy(), approval.outboundMessageId());
        jdbc.sql("""
                insert into message_approval
                    (id, tenant_id, conversation_id, source_receipt_id, version, conversation_version,
                     disposition_route, reason_code, status, draft_text, draft_may_be_approved, created_at, updated_at,
                     reviewed_by, outbound_message_id)
                values (:id, :tenantId, :conversationId, :receiptId, :version, :conversationVersion,
                        :route, :reason, :status, :draft, :sendable, :createdAt, :updatedAt, null, null)
                """)
                .param("id", persisted.id()).param("tenantId", persisted.tenantId())
                .param("conversationId", persisted.conversationId()).param("receiptId", persisted.sourceReceiptId())
                .param("version", persisted.version()).param("conversationVersion", persisted.conversationVersion())
                .param("route", persisted.disposition().route().name()).param("reason", persisted.disposition().reason().name())
                .param("status", persisted.status().name()).param("draft", persisted.draftText())
                .param("sendable", persisted.draftMayBeApproved()).param("createdAt", Timestamp.from(persisted.createdAt()))
                .param("updatedAt", Timestamp.from(persisted.updatedAt())).update();
        jdbc.sql("""
                insert into message_approval_hold(tenant_id, conversation_id, approval_id, created_at)
                values (:tenantId, :conversationId, :approvalId, :createdAt)
                """)
                .param("tenantId", persisted.tenantId()).param("conversationId", persisted.conversationId())
                .param("approvalId", persisted.id()).param("createdAt", Timestamp.from(persisted.createdAt())).update();
        return persisted;
    }

    @Override public void invalidateForInbound(UUID tenantId, UUID conversationId, java.time.Instant receivedAt) {
        var activeApprovalId = jdbc.sql("select approval_id from message_approval_hold where tenant_id=:tenant and conversation_id=:conversation")
                .param("tenant", tenantId).param("conversation", conversationId).query(UUID.class).optional();
        if (activeApprovalId.isEmpty()) return;
        var now = Timestamp.from(receivedAt);
        var nextConversationVersion = jdbc.sql("""
                update conversation set version=version+1, updated_at=:now
                 where tenant_id=:tenant and id=:conversation
                returning version
                """)
                .param("now", now).param("tenant", tenantId).param("conversation", conversationId)
                .query(Long.class).single();
        jdbc.sql("""
                update message_approval
                   set status='SUPERSEDED', version=version+1, conversation_version=:conversationVersion,
                       draft_text=null, draft_may_be_approved=false, updated_at=:now
                 where tenant_id=:tenant and conversation_id=:conversation
                   and status in ('PENDING','REJECTED')
                """)
                .param("conversationVersion", nextConversationVersion).param("now", now)
                .param("tenant", tenantId).param("conversation", conversationId).update();
        jdbc.sql("delete from message_approval_hold where tenant_id=:tenant and conversation_id=:conversation and approval_id=:approval")
                .param("tenant", tenantId).param("conversation", conversationId).param("approval", activeApprovalId.get()).update();
    }

    @Override public List<MessageApproval> list(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                select * from message_approval
                 where tenant_id = :tenantId and conversation_id = :conversationId
                 order by created_at desc, id
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query((rs, row) -> map(rs)).list();
    }

    @Override public boolean replace(MessageApproval current, MessageApproval next) {
        if (!current.id().equals(next.id()) || !current.tenantId().equals(next.tenantId())
                || !current.conversationId().equals(next.conversationId())
                || next.version() != current.version() + 1
                || next.sourceReceiptId() == null || !next.sourceReceiptId().equals(current.sourceReceiptId())) {
            throw new IllegalArgumentException("approval identity cannot change");
        }
        int changed = jdbc.sql("""
                update message_approval
                   set version = :nextVersion, conversation_version = :nextConversationVersion,
                       status = :status, draft_text = :draft, draft_may_be_approved = :sendable,
                       updated_at = :updatedAt, reviewed_by = :reviewedBy, outbound_message_id = :outboundId
                 where id = :id and tenant_id = :tenantId and conversation_id = :conversationId
                   and version = :currentVersion and conversation_version = :currentConversationVersion
                   and status = :currentStatus
                """)
                .param("nextVersion", next.version()).param("nextConversationVersion", next.conversationVersion())
                .param("status", next.status().name()).param("draft", next.draftText()).param("sendable", next.draftMayBeApproved())
                .param("updatedAt", Timestamp.from(next.updatedAt())).param("reviewedBy", next.reviewedBy())
                .param("outboundId", next.outboundMessageId()).param("id", current.id())
                .param("tenantId", current.tenantId()).param("conversationId", current.conversationId())
                .param("currentVersion", current.version()).param("currentConversationVersion", current.conversationVersion())
                .param("currentStatus", current.status().name()).update();
        if (changed != 1) return false;
        if (next.blocksAutomatedReplies()) {
            jdbc.sql("""
                    insert into message_approval_hold(tenant_id, conversation_id, approval_id, created_at)
                    values (:tenantId, :conversationId, :approvalId, :createdAt)
                    on conflict (tenant_id, conversation_id) do update
                       set approval_id = excluded.approval_id, created_at = excluded.created_at
                    """)
                    .param("tenantId", next.tenantId()).param("conversationId", next.conversationId())
                    .param("approvalId", next.id()).param("createdAt", Timestamp.from(next.updatedAt())).update();
        } else {
            jdbc.sql("delete from message_approval_hold where tenant_id=:tenantId and conversation_id=:conversationId and approval_id=:approvalId")
                    .param("tenantId", next.tenantId()).param("conversationId", next.conversationId())
                    .param("approvalId", next.id()).update();
        }
        return true;
    }

    @Override public void recordAudit(UUID tenantId, UUID approvalId, UUID actorId, AuditRecord audit, java.time.Instant at) {
        jdbc.sql("""
                insert into message_approval_action_audit
                    (id, tenant_id, conversation_id, approval_id, actor_id, action, approval_version,
                     reason_code, source_receipt_id, outbound_message_id, occurred_at)
                select :id, :tenantId, conversation_id, :approvalId, :actorId, :action, :version,
                       reason_code, :sourceReceiptId, :outboundId, :occurredAt
                  from message_approval where tenant_id=:tenantId and id=:approvalId
                """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("approvalId", approvalId)
                .param("actorId", actorId).param("action", audit.action()).param("version", audit.approvalVersion())
                .param("sourceReceiptId", audit.sourceReceiptId()).param("outboundId", audit.outboundMessageId())
                .param("occurredAt", Timestamp.from(at)).update();
    }

    private static MessageApproval map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MessageApproval(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getObject("source_receipt_id", UUID.class),
                rs.getLong("version"), rs.getLong("conversation_version"),
                new AdvisorResponseDisposition(AdvisorResponseDisposition.Route.valueOf(rs.getString("disposition_route")),
                        AdvisorResponseDisposition.Reason.valueOf(rs.getString("reason_code"))),
                MessageApproval.Status.valueOf(rs.getString("status")), rs.getString("draft_text"),
                rs.getBoolean("draft_may_be_approved"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getObject("reviewed_by", UUID.class),
                rs.getObject("outbound_message_id", UUID.class));
    }
}
