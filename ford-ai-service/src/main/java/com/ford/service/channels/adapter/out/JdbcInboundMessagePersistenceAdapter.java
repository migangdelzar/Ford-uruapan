package com.ford.service.channels.adapter.out;

import com.ford.service.channels.application.port.out.InboundMessagePersistencePort;
import com.ford.service.channels.domain.InboundMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcInboundMessagePersistenceAdapter implements InboundMessagePersistencePort {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcInboundMessagePersistenceAdapter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public UUID persist(InboundMessage message) {
        return persist(message, UUID.randomUUID());
    }

    @Override
    @Transactional
    public UUID persist(InboundMessage message, UUID receiptId) {
        var now = Timestamp.from(Instant.now());
        var conversationId = jdbc.sql("""
                insert into conversation
                    (id, tenant_id, channel, provider_account_id, participant_id,
                     provider_conversation_id, state,
                     version, created_at, updated_at)
                values (:conversationId, :tenantId, :channel, :providerAccountId, :participantId,
                        :providerConversationId,
                        'BOT_ACTIVE', 0, :now, :now)
                on conflict (tenant_id, channel, provider_account_id, participant_id)
                do update set updated_at = excluded.updated_at
                returning id
                """)
                .param("conversationId", message.conversationId())
                .param("tenantId", message.tenantId())
                .param("channel", message.channel().name())
                .param("providerAccountId", message.providerAccountId())
                .param("participantId", message.participantId())
                .param("providerConversationId", message.participantId())
                .param("now", now)
                .query(UUID.class)
                .single();

        jdbc.sql("""
                insert into channel_message
                    (id, binding_id, conversation_id, tenant_id, channel,
                     provider_message_id, participant_id, content, received_at,
                     raw_payload_reference, message_kind, attachment_metadata)
                values (:receiptId, :bindingId, :conversationId, :tenantId, :channel,
                        :providerMessageId, :participantId, :content, :receivedAt,
                        :rawPayloadReference, :messageKind, cast(:attachmentMetadata as jsonb))
                on conflict (tenant_id, channel, provider_message_id) do nothing
                """)
                .param("receiptId", receiptId)
                .param("bindingId", message.bindingId())
                .param("conversationId", conversationId)
                .param("tenantId", message.tenantId())
                .param("channel", message.channel().name())
                .param("providerMessageId", message.providerMessageId())
                .param("participantId", message.participantId())
                .param("content", message.text())
                .param("receivedAt", Timestamp.from(message.receivedAt()))
                .param("rawPayloadReference", message.rawPayloadReference())
                .param("messageKind", message.kind().name())
                .param("attachmentMetadata", attachmentMetadata(message))
                .update();

        jdbc.sql("""
                insert into conversation_message
                    (id, conversation_id, direction, provider_message_id, content, received_at, sender_type)
                values (:id, :conversationId, 'INBOUND', :providerMessageId, :content, :receivedAt, 'CUSTOMER')
                on conflict (conversation_id, provider_message_id) do nothing
                """)
                .param("id", UUID.randomUUID())
                .param("conversationId", conversationId)
                .param("providerMessageId", message.providerMessageId())
                .param("content", message.text())
                .param("receivedAt", Timestamp.from(message.receivedAt()))
                .update();
        return conversationId;
    }

    private String attachmentMetadata(InboundMessage message) {
        try {
            return objectMapper.writeValueAsString(message.attachments());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize inbound attachment metadata", exception);
        }
    }
}
