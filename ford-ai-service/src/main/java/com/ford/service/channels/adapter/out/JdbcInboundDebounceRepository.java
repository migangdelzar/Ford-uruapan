package com.ford.service.channels.adapter.out;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.application.port.out.InboundDebounceRepository;
import com.ford.service.channels.domain.Channel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable, restart-safe debounce schedule. Upserts one row per conversation and
 * coalesces the burst into the earliest due time so an immediate bypass can pull
 * the response opportunity forward.
 */
@Repository
public class JdbcInboundDebounceRepository implements InboundDebounceRepository {

    private final JdbcClient jdbc;

    public JdbcInboundDebounceRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    @Transactional
    public void schedule(InboundMessageAccepted event, Instant dueAt) {
        var now = Instant.now();
        jdbc.sql("""
                insert into inbound_debounce
                    (tenant_id, conversation_id, receipt_id, channel, provider_message_id,
                     accepted_at, due_at, updated_at)
                values (:tenantId, :conversationId, :receiptId, :channel, :providerMessageId,
                        :acceptedAt, :dueAt, :updatedAt)
                on conflict (tenant_id, conversation_id) do update
                    set receipt_id = excluded.receipt_id,
                        channel = excluded.channel,
                        provider_message_id = excluded.provider_message_id,
                        accepted_at = excluded.accepted_at,
                        due_at = least(inbound_debounce.due_at, excluded.due_at),
                        updated_at = excluded.updated_at
                """)
                .param("tenantId", event.tenantId())
                .param("conversationId", event.conversationId())
                .param("receiptId", event.receiptId())
                .param("channel", event.channel().name())
                .param("providerMessageId", event.providerMessageId())
                .param("acceptedAt", Timestamp.from(event.acceptedAt()))
                .param("dueAt", Timestamp.from(dueAt))
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    @Override
    public boolean isDue(UUID tenantId, UUID conversationId, UUID receiptId, Instant now) {
        return jdbc.sql("""
                        select count(*) from inbound_debounce
                        where tenant_id = :tenantId and conversation_id = :conversationId
                          and receipt_id = :receiptId and due_at <= :now
                        """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("receiptId", receiptId)
                .param("now", Timestamp.from(now))
                .query(Integer.class).single() > 0;
    }

    @Override
    @Transactional
    public List<InboundMessageAccepted> claimDue(Instant now, int limit) {
        return jdbc.sql("""
                        with due as (
                            select tenant_id, conversation_id
                            from inbound_debounce
                            where due_at <= :now
                            order by due_at
                            limit :limit
                            for update skip locked
                        )
                        delete from inbound_debounce target
                        using due
                        where target.tenant_id = due.tenant_id
                          and target.conversation_id = due.conversation_id
                        returning target.receipt_id, target.tenant_id, target.conversation_id,
                                  target.channel, target.provider_message_id, target.accepted_at
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, rowNum) -> new InboundMessageAccepted(
                        rs.getObject("receipt_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        Channel.valueOf(rs.getString("channel")),
                        rs.getString("provider_message_id"),
                        rs.getTimestamp("accepted_at").toInstant()))
                .list();
    }
}
