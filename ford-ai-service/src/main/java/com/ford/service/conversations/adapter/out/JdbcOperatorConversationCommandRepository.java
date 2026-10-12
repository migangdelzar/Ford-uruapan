package com.ford.service.conversations.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.application.port.out.OperatorConversationCommandRepository;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.outbox.OutboxEvent;
import com.ford.service.outbox.OutboxStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOperatorConversationCommandRepository implements OperatorConversationCommandRepository {

    private final JdbcClient jdbc;
    private final OutboxStore outbox;
    private final ObjectMapper objectMapper;

    public JdbcOperatorConversationCommandRepository(JdbcClient jdbc, OutboxStore outbox, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CommandTarget> find(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                select id, tenant_id, channel, provider_conversation_id, state, version
                from conversation
                where id = :conversationId and tenant_id = :tenantId
                """)
                .param("conversationId", conversationId).param("tenantId", tenantId)
                .query((rs, rowNum) -> new CommandTarget(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class),
                        Channel.valueOf(rs.getString("channel")), rs.getString("provider_conversation_id"),
                        ConversationState.valueOf(rs.getString("state")), rs.getLong("version")))
                .optional();
    }

    @Override
    @Transactional
    public boolean bumpVersionForMemoryRestart(UUID tenantId, UUID conversationId, long expectedVersion) {
        return jdbc.sql("""
                update conversation
                   set version = version + 1, updated_at = :updatedAt
                 where id = :conversationId and tenant_id = :tenantId and version = :expectedVersion
                   and state in ('BOT_ACTIVE', 'LEAD_CAPTURE')
                """)
                .param("conversationId", conversationId)
                .param("tenantId", tenantId)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", Timestamp.from(Instant.now()))
                .update() == 1;
    }

    @Override
    @Transactional
    public CommandReceipt appendMessage(CommandMessage message) {
        var target = find(message.tenantId(), message.conversationId())
                .orElseThrow(() -> new java.util.NoSuchElementException("conversation not found"));
        var updated = jdbc.sql("""
                update conversation
                   set version = version + 1, updated_at = :updatedAt
                 where id = :conversationId and tenant_id = :tenantId and version = :expectedVersion
                   and state in ('BOT_ACTIVE', 'LEAD_CAPTURE', 'HUMAN_ACTIVE')
                """)
                .param("conversationId", message.conversationId()).param("tenantId", message.tenantId())
                .param("expectedVersion", message.expectedVersion())
                .param("updatedAt", Timestamp.from(Instant.now())).update();
        if (updated != 1) {
            throw new IllegalStateException("conversation version is stale or state does not accept message");
        }
        var now = Instant.now();
        jdbc.sql("""
                insert into conversation_message
                    (id, conversation_id, direction, provider_message_id, content, received_at)
                values (:id, :conversationId, 'OUTBOUND', :providerMessageId, :content, :receivedAt)
                """)
                .param("id", message.messageId()).param("conversationId", message.conversationId())
                .param("providerMessageId", message.providerMessageId()).param("content", message.content())
                .param("receivedAt", Timestamp.from(now)).update();
        if (!message.internalNote()) {
            outbox.append(new OutboxEvent(
                    UUID.randomUUID(), message.tenantId(), "conversation", message.conversationId(),
                    "OUTBOUND_DELIVERY_REQUESTED", payload(target, message), OutboxEvent.Status.PENDING,
                    0, now, null, null,
                    message.tenantId() + ":conversation:" + message.conversationId() + ":" + message.providerMessageId(),
                    null, null));
        }
        return new CommandReceipt(message.messageId(), message.conversationId(), true,
                message.internalNote() ? "INTERNAL" : "QUEUED", message.expectedVersion() + 1,
                message.internalNote(), now);
    }

    private String payload(CommandTarget target, CommandMessage message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "conversationId", message.conversationId().toString(),
                    "channel", target.channel().name(),
                    "recipientId", target.recipientId(),
                    "text", message.content()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize outbound payload", exception);
        }
    }
}
