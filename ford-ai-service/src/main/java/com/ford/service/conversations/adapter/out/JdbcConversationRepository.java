package com.ford.service.conversations.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationTurn;
import com.ford.service.conversations.domain.HandoffDecision;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcConversationRepository implements ConversationRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcConversationRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ConversationSnapshot> find(UUID conversationId) {
        return jdbc.sql("""
                select c.id, c.tenant_id, c.channel, c.provider_conversation_id,
                       c.state, c.version, m.content as inbound_content,
                       m.provider_message_id as inbound_provider_message_id
                from conversation c
                left join lateral (
                    select content, provider_message_id
                    from channel_message
                    where conversation_id = c.id
                    order by received_at desc
                    limit 1
                ) m on true
                where c.id = :conversationId
                """)
                .param("conversationId", conversationId)
                .query((rs, rowNum) -> new ConversationSnapshot(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        Channel.valueOf(rs.getString("channel")),
                        rs.getString("provider_conversation_id"),
                        ConversationState.valueOf(rs.getString("state")),
                        rs.getLong("version"),
                        rs.getString("inbound_content"),
                        rs.getString("inbound_provider_message_id")))
                .optional();
    }

    @Override
    @Transactional
    public boolean completeBotTurn(UUID conversationId, long expectedVersion, ConversationTurn turn) {
        var updated = jdbc.sql("""
                update conversation
                set version = version + 1, updated_at = :updatedAt
                where id = :conversationId and version = :expectedVersion
                  and state in ('BOT_ACTIVE', 'LEAD_CAPTURE')
                """)
                .param("conversationId", conversationId)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", Timestamp.from(Instant.now()))
                .update();
        if (updated != 1) {
            return false;
        }

        var delivery = jdbc.sql("""
                select tenant_id, channel, provider_conversation_id
                from conversation
                where id = :conversationId
                """)
                .param("conversationId", conversationId)
                .query((rs, rowNum) -> new DeliveryTarget(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("channel"),
                        rs.getString("provider_conversation_id")))
                .single();
        var messageId = "assistant:" + UUID.randomUUID();
        jdbc.sql("""
                insert into conversation_message
                    (id, conversation_id, direction, provider_message_id, content, received_at)
                values (:id, :conversationId, 'OUTBOUND', :providerMessageId, :content, :receivedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("conversationId", conversationId)
                .param("providerMessageId", messageId)
                .param("content", turn.text())
                .param("receivedAt", Timestamp.from(Instant.now()))
                .update();
        jdbc.sql("""
                insert into outbox_event
                    (id, tenant_id, aggregate_type, aggregate_id, event_type,
                     payload, status, attempts, available_at, idempotency_key)
                values (:id, :tenantId, 'conversation', :aggregateId, 'OUTBOUND_DELIVERY_REQUESTED',
                        cast(:payload as jsonb), 'PENDING', 0, :availableAt, :idempotencyKey)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", delivery.tenantId())
                .param("aggregateId", conversationId)
                .param("payload", payload(delivery, conversationId, turn.text()))
                .param("idempotencyKey", delivery.tenantId() + ":conversation:" + conversationId + ":" + messageId)
                .param("availableAt", Timestamp.from(Instant.now()))
                .update();
        return true;
    }

    @Override
    @Transactional
    public boolean requestHumanHandoff(UUID conversationId, long expectedVersion, HandoffDecision decision) {
        var updated = jdbc.sql("""
                update conversation
                set state = 'HUMAN_PENDING', version = version + 1, updated_at = :updatedAt
                where id = :conversationId and version = :expectedVersion
                  and state in ('BOT_ACTIVE', 'LEAD_CAPTURE')
                """)
                .param("conversationId", conversationId)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", Timestamp.from(Instant.now()))
                .update();
        if (updated != 1) {
            return false;
        }

        var tenantId = jdbc.sql("select tenant_id from conversation where id = :conversationId")
                .param("conversationId", conversationId)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                insert into handoff_ticket
                    (id, tenant_id, conversation_id, status, reason, version, created_at)
                values (:id, :tenantId, :conversationId, 'HUMAN_PENDING', :reason, 0, :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("reason", decision.reason())
                .param("createdAt", Timestamp.from(Instant.now()))
                .update();
        return true;
    }

    @Override
    public ConversationState state(UUID conversationId) {
        return jdbc.sql("select state from conversation where id = :conversationId")
                .param("conversationId", conversationId)
                .query(String.class)
                .optional()
                .map(ConversationState::valueOf)
                .orElseThrow();
    }

    @Override
    public boolean recordMessageIfNew(UUID conversationId, String providerMessageId) {
        return jdbc.sql("select count(*) from conversation_message where conversation_id = :conversationId and provider_message_id = :providerMessageId")
                .param("conversationId", conversationId)
                .param("providerMessageId", providerMessageId)
                .query(Integer.class)
                .single() == 0;
    }

    @Override
    @Transactional
    public void transition(UUID conversationId, ConversationState expected, ConversationState target) {
        var updated = jdbc.sql("""
                update conversation
                set state = :target, version = version + 1, updated_at = :updatedAt
                where id = :conversationId and state = :expected
                """)
                .param("conversationId", conversationId)
                .param("expected", expected.name())
                .param("target", target.name())
                .param("updatedAt", Timestamp.from(Instant.now()))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Conversation transition lost its version race");
        }
    }

    private String payload(DeliveryTarget delivery, UUID conversationId, String text) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "conversationId", conversationId,
                    "channel", delivery.channel(),
                    "recipientId", delivery.recipientId(),
                    "text", text));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize outbound delivery", exception);
        }
    }

    private record DeliveryTarget(UUID tenantId, String channel, String recipientId) {
    }
}
