package com.ford.service.channels.adapter.out;

import com.ford.service.channels.application.port.out.InboundMessageDeduplicationPort;
import com.ford.service.channels.domain.InboundMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

@Repository
public class JdbcInboundMessageDeduplicationAdapter implements InboundMessageDeduplicationPort {
    private final JdbcClient jdbc;

    public JdbcInboundMessageDeduplicationAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean recordIfNew(InboundMessage message) {
        return recordIfNew(message, UUID.randomUUID());
    }

    @Override
    public boolean recordIfNew(InboundMessage message, UUID receiptId) {
        return jdbc.sql("""
                insert into channel_delivery
                    (receipt_id, binding_id, conversation_id, tenant_id, channel,
                     provider_message_id, received_at, raw_payload_reference)
                values (:receiptId, :bindingId, :conversationId, :tenantId, :channel,
                        :providerMessageId, :receivedAt, :rawPayloadReference)
                on conflict (tenant_id, channel, provider_message_id) do nothing
                """)
                .param("receiptId", receiptId)
                .param("bindingId", message.bindingId())
                .param("conversationId", message.conversationId())
                .param("tenantId", message.tenantId())
                .param("channel", message.channel().name())
                .param("providerMessageId", message.providerMessageId())
                .param("receivedAt", Timestamp.from(message.receivedAt()))
                .param("rawPayloadReference", message.rawPayloadReference())
                .update() == 1;
    }
}
