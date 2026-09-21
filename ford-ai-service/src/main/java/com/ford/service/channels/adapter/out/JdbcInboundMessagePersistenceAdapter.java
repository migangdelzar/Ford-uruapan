package com.ford.service.channels.adapter.out;

import com.ford.service.channels.application.port.out.InboundMessagePersistencePort;
import com.ford.service.channels.domain.InboundMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcInboundMessagePersistenceAdapter implements InboundMessagePersistencePort {
    private final JdbcClient jdbc;

    public JdbcInboundMessagePersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void persist(InboundMessage message) {
        persist(message, UUID.randomUUID());
    }

    @Override
    @Transactional
    public void persist(InboundMessage message, UUID receiptId) {
        var now = Timestamp.from(Instant.now());
        jdbc.sql("""
                insert into conversation
                    (id, tenant_id, channel, provider_account_id, participant_id,
                     provider_conversation_id, state,
                     version, created_at, updated_at)
                values (:conversationId, :tenantId, :channel, :providerAccountId, :participantId,
                        :providerConversationId,
                        'BOT_ACTIVE', 0, :now, :now)
                on conflict (tenant_id, channel, provider_account_id, participant_id)
                do update set updated_at = excluded.updated_at
                """)
                .param("conversationId", message.conversationId())
                .param("tenantId", message.tenantId())
                .param("channel", message.channel().name())
                .param("providerAccountId", message.providerAccountId())
                .param("participantId", message.participantId())
                .param("providerConversationId", message.participantId())
                .param("now", now)
                .update();

        jdbc.sql("""
                insert into channel_message
                    (id, binding_id, conversation_id, tenant_id, channel,
                     provider_message_id, participant_id, content, received_at,
                     raw_payload_reference)
                values (:receiptId, :bindingId, :conversationId, :tenantId, :channel,
                        :providerMessageId, :participantId, :content, :receivedAt,
                        :rawPayloadReference)
                on conflict (tenant_id, channel, provider_message_id) do nothing
                """)
                .param("receiptId", receiptId)
                .param("bindingId", message.bindingId())
                .param("conversationId", message.conversationId())
                .param("tenantId", message.tenantId())
                .param("channel", message.channel().name())
                .param("providerMessageId", message.providerMessageId())
                .param("participantId", message.participantId())
                .param("content", message.text())
                .param("receivedAt", Timestamp.from(message.receivedAt()))
                .param("rawPayloadReference", message.rawPayloadReference())
                .update();

        jdbc.sql("""
                insert into conversation_message
                    (id, conversation_id, direction, provider_message_id, content, received_at)
                values (:id, :conversationId, 'INBOUND', :providerMessageId, :content, :receivedAt)
                on conflict (conversation_id, provider_message_id) do nothing
                """)
                .param("id", UUID.randomUUID())
                .param("conversationId", message.conversationId())
                .param("providerMessageId", message.providerMessageId())
                .param("content", message.text())
                .param("receivedAt", Timestamp.from(message.receivedAt()))
                .update();
    }
}
