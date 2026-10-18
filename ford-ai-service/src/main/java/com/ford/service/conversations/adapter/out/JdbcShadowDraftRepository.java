package com.ford.service.conversations.adapter.out;

import com.ford.service.conversations.application.port.out.ShadowDraftRepository;
import com.ford.service.conversations.domain.ShadowDraft;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists shadow drafts only. There is intentionally no path from here to a
 * channel outbox, delivery provider, or customer-visible message.
 */
@Repository
public class JdbcShadowDraftRepository implements ShadowDraftRepository {

    private final JdbcClient jdbc;

    public JdbcShadowDraftRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(ShadowDraft draft) {
        Objects.requireNonNull(draft, "shadow draft is required");
        jdbc.sql("""
                merge into conversation_shadow_draft as target
                using (values (:id, :tenantId, :conversationId, :receiptId, :inboundProviderMessageId,
                               :draftText, 'PENDING', cast(:createdAt as timestamp with time zone),
                               cast(:expiresAt as timestamp with time zone)))
                    as incoming(id, tenant_id, conversation_id, receipt_id, inbound_provider_message_id,
                                draft_text, review_status, created_at, expires_at)
                on target.tenant_id = incoming.tenant_id
                   and target.conversation_id = incoming.conversation_id
                   and target.inbound_provider_message_id = incoming.inbound_provider_message_id
                when matched then update set
                    receipt_id = incoming.receipt_id,
                    draft_text = incoming.draft_text,
                    created_at = incoming.created_at,
                    expires_at = incoming.expires_at
                when not matched then insert
                    (id, tenant_id, conversation_id, receipt_id, inbound_provider_message_id,
                     draft_text, review_status, created_at, expires_at)
                values (incoming.id, incoming.tenant_id, incoming.conversation_id, incoming.receipt_id,
                        incoming.inbound_provider_message_id, incoming.draft_text, incoming.review_status,
                        incoming.created_at, incoming.expires_at)
                """)
                .param("id", draft.id())
                .param("tenantId", draft.tenantId())
                .param("conversationId", draft.conversationId())
                .param("receiptId", draft.receiptId())
                .param("inboundProviderMessageId", draft.inboundProviderMessageId())
                .param("draftText", draft.text())
                .param("createdAt", Timestamp.from(draft.createdAt()))
                .param("expiresAt", Timestamp.from(draft.expiresAt()))
                .update();
    }

    @Override
    public List<ShadowDraft> list(UUID tenantId, UUID conversationId, int limit) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(conversationId, "conversationId is required");
        var boundedLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.sql("""
                select id, tenant_id, conversation_id, receipt_id, inbound_provider_message_id,
                       draft_text, review_status, created_at, expires_at
                  from conversation_shadow_draft
                 where tenant_id = :tenantId
                   and conversation_id = :conversationId
                   and expires_at > now()
                 order by created_at desc
                 limit :limit
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("limit", boundedLimit)
                .query((rs, rowNum) -> new ShadowDraft(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("receipt_id", UUID.class),
                        rs.getString("inbound_provider_message_id"),
                        rs.getString("draft_text"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("review_status")))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean review(UUID tenantId, UUID conversationId, UUID draftId, UUID actorId, String decision, String note) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(conversationId, "conversationId is required");
        Objects.requireNonNull(draftId, "draftId is required");
        var updated = jdbc.sql("""
                update conversation_shadow_draft
                   set review_status = :decision,
                       review_note = :note,
                       reviewed_by = :actorId,
                       reviewed_at = now()
                 where id = :draftId
                   and tenant_id = :tenantId
                   and conversation_id = :conversationId
                   and review_status = 'PENDING'
                   and expires_at > now()
                """)
                .param("decision", decision)
                .param("note", note == null ? "" : note)
                .param("actorId", actorId)
                .param("draftId", draftId)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .update();
        return updated > 0;
    }
}
