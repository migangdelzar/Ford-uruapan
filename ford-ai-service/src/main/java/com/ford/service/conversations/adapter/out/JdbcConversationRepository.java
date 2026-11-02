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
        return findScoped(null, conversationId);
    }

    @Override
    public Optional<ConversationSnapshot> find(UUID tenantId, UUID conversationId) {
        return findScoped(tenantId, conversationId);
    }

    private Optional<ConversationSnapshot> findScoped(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                select c.id, c.tenant_id, c.channel, c.provider_conversation_id,
                       c.provider_account_id,
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
                  and (cast(:tenantId as uuid) is null or c.tenant_id = :tenantId)
                """)
                .param("conversationId", conversationId)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new ConversationSnapshot(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        Channel.valueOf(rs.getString("channel")),
                        rs.getString("provider_account_id"),
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
        var snapshot = find(conversationId).orElse(null);
        return snapshot != null && completeBotTurn(snapshot.tenantId(), conversationId, expectedVersion,
                UUID.randomUUID().toString(), turn);
    }

    @Override
    @Transactional
    public boolean completeBotTurn(UUID tenantId, UUID conversationId, long expectedVersion,
                                   String inboundProviderMessageId, ConversationTurn turn) {
        var responseProviderMessageId = assistantMessageId(tenantId, conversationId, inboundProviderMessageId);
        var updated = jdbc.sql("""
                update conversation
                set version = version + 1, updated_at = :updatedAt
                where id = :conversationId and tenant_id = :tenantId and version = :expectedVersion
                  and state in ('BOT_ACTIVE', 'LEAD_CAPTURE')
                  and not exists (select 1 from conversation_message
                                  where conversation_id = :conversationId
                                    and provider_message_id = :responseProviderMessageId)
                  and not exists (select 1 from message_approval_hold
                                  where tenant_id = :tenantId and conversation_id = :conversationId)
                """)
                .param("conversationId", conversationId)
                .param("tenantId", tenantId)
                .param("expectedVersion", expectedVersion)
                .param("responseProviderMessageId", responseProviderMessageId)
                .param("updatedAt", Timestamp.from(Instant.now()))
                .update();
        if (updated != 1) {
            return false;
        }

        var delivery = jdbc.sql("""
                select tenant_id, channel, provider_conversation_id
                from conversation
                where id = :conversationId and tenant_id = :tenantId
                """)
                .param("conversationId", conversationId)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new DeliveryTarget(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("channel"),
                        rs.getString("provider_conversation_id")))
                .single();
        var messageId = responseProviderMessageId;
        jdbc.sql("""
                insert into conversation_message
                    (id, conversation_id, direction, provider_message_id, content, received_at, sender_type)
                values (:id, :conversationId, 'OUTBOUND', :providerMessageId, :content, :receivedAt, 'BOT')
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
                .param("payload", payload(delivery, conversationId, turn.text(), turn.replyOptions()))
                .param("idempotencyKey", "conversation-turn:" + responseProviderMessageId)
                .param("availableAt", Timestamp.from(Instant.now()))
                .update();
        return true;
    }

    @Override
    @Transactional
    public boolean requestHumanHandoff(UUID conversationId, long expectedVersion, HandoffDecision decision) {
        var snapshot = find(conversationId).orElse(null);
        return snapshot != null && requestHumanHandoff(snapshot.tenantId(), conversationId,
                expectedVersion, decision);
    }

    @Override
    @Transactional
    public boolean requestHumanHandoff(UUID tenantId, UUID conversationId, long expectedVersion,
                                       HandoffDecision decision) {
        return requestHumanHandoff(tenantId, conversationId, expectedVersion, null, decision, null);
    }

    @Override
    @Transactional
    public boolean requestHumanHandoff(UUID tenantId, UUID conversationId, long expectedVersion,
                                       String inboundProviderMessageId, HandoffDecision decision,
                                       ConversationTurn confirmation) {
        var updated = jdbc.sql("""
                update conversation
                set state = 'HUMAN_PENDING', version = version + 1, updated_at = :updatedAt
                where id = :conversationId and tenant_id = :tenantId and version = :expectedVersion
                  and state in ('BOT_ACTIVE', 'LEAD_CAPTURE')
                """)
                .param("conversationId", conversationId)
                .param("tenantId", tenantId)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", Timestamp.from(Instant.now()))
                .update();
        if (updated != 1) {
            return false;
        }

        var forgottenAt = jdbc.sql("""
                select forgotten_at from handoff_contact_forget_fence
                where tenant_id=:tenantId and conversation_id=:conversationId
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
        var hasFreshConsent = forgottenAt != null && decision.packet().alternateContact()
                .map(contact -> contact.consentedAt().isAfter(forgottenAt)).orElse(false);
        var packet = forgottenAt != null && !hasFreshConsent
                ? decision.packet().withAlternateContact(null) : decision.packet();

        jdbc.sql("""
                insert into handoff_ticket
                    (id, tenant_id, conversation_id, status, priority, reason, idempotency_key, version,
                     created_at, packet, packet_partial, packet_completeness, internal_note)
                values (:id, :tenantId, :conversationId, 'HUMAN_PENDING', :priority, :reason,
                        :idempotencyKey, 0, :createdAt, cast(:packet as jsonb), :packetPartial, :packetCompleteness,
                        cast(:internalNote as jsonb))
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("priority", decision.priority())
                .param("reason", decision.reason())
                .param("idempotencyKey", decision.idempotencyKey())
                .param("createdAt", Timestamp.from(Instant.now()))
                .param("packet", packetJson(packet))
                .param("packetPartial", packet.partial())
                .param("packetCompleteness", packet.completeness())
                .param("internalNote", internalNoteJson(decision.internalNote()))
                .update();
        if (confirmation != null && inboundProviderMessageId != null) {
            var responseProviderMessageId = assistantMessageId(tenantId, conversationId, inboundProviderMessageId);
            jdbc.sql("""
                    insert into conversation_message
                        (id, conversation_id, direction, provider_message_id, content, received_at, sender_type)
                    values (:id, :conversationId, 'OUTBOUND', :providerMessageId, :content, :receivedAt, 'BOT')
                    """)
                    .param("id", UUID.randomUUID()).param("conversationId", conversationId)
                    .param("providerMessageId", responseProviderMessageId).param("content", confirmation.text())
                    .param("receivedAt", Timestamp.from(Instant.now())).update();
            var target = jdbc.sql("select tenant_id, channel, provider_conversation_id from conversation " +
                            "where id = :conversationId and tenant_id = :tenantId")
                    .param("conversationId", conversationId).param("tenantId", tenantId)
                    .query((rs, rowNum) -> new DeliveryTarget(rs.getObject("tenant_id", UUID.class),
                            rs.getString("channel"), rs.getString("provider_conversation_id"))).single();
            jdbc.sql("""
                    insert into outbox_event
                        (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status,
                         attempts, available_at, idempotency_key)
                    values (:id, :tenantId, 'conversation', :aggregateId, 'OUTBOUND_DELIVERY_REQUESTED',
                            cast(:payload as jsonb), 'PENDING', 0, :availableAt, :idempotencyKey)
                    """)
                    .param("id", UUID.randomUUID()).param("tenantId", tenantId)
                    .param("aggregateId", conversationId).param("payload", payload(target, conversationId,
                            confirmation.text(), confirmation.replyOptions()))
                    .param("availableAt", Timestamp.from(Instant.now()))
                    .param("idempotencyKey", "conversation-turn:" + responseProviderMessageId).update();
        }
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
    public boolean turnAlreadyCompleted(UUID tenantId, UUID conversationId, String inboundProviderMessageId) {
        var responseProviderMessageId = assistantMessageId(tenantId, conversationId, inboundProviderMessageId);
        return jdbc.sql("""
                select exists (
                    select 1 from conversation_message message
                    join conversation owner on owner.id = message.conversation_id
                    where message.conversation_id = :conversationId
                      and owner.tenant_id = :tenantId
                      and message.provider_message_id = :responseProviderMessageId
                )
                """)
                .param("conversationId", conversationId)
                .param("tenantId", tenantId)
                .param("responseProviderMessageId", responseProviderMessageId)
                .query(Boolean.class)
                .single();
    }

    private static String assistantMessageId(UUID tenantId, UUID conversationId, String providerMessageId) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    (tenantId + ":" + conversationId + ":" + providerMessageId)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "assistant:inbound:" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    private String packetJson(com.ford.service.conversations.api.BdcHandoffPacket packet) {
        try {
            return objectMapper.writeValueAsString(packet.arguments());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize BDC handoff packet", exception);
        }
    }

    private String internalNoteJson(com.ford.service.conversations.api.InternalHandoffNote note) {
        if (note == null) return null;
        try {
            return objectMapper.writeValueAsString(note);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize internal handoff note", exception);
        }
    }

    private String payload(DeliveryTarget delivery, UUID conversationId, String text,
                          java.util.List<com.ford.service.channels.domain.ReplyOption> replyOptions) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "conversationId", conversationId,
                    "channel", delivery.channel(),
                    "recipientId", delivery.recipientId(),
                    "text", text,
                    "replyOptions", replyOptions == null ? java.util.List.of() : replyOptions));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize outbound delivery", exception);
        }
    }

    private record DeliveryTarget(UUID tenantId, String channel, String recipientId) {
    }
}
