package com.ford.service.channels.adapter.out;

import com.ford.service.channels.application.port.out.InboundMessagePersistencePort;
import com.ford.service.channels.domain.InboundMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public class JdbcInboundMessagePersistenceAdapter implements InboundMessagePersistencePort {
    private final JdbcClient jdbc;

    public JdbcInboundMessagePersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void persist(InboundMessage message) {
        jdbc.sql("""
                insert into channel_message (id, tenant_id, channel, provider_message_id,
                    participant_id, content, received_at, raw_payload_reference)
                values (gen_random_uuid(), :tenantId, :channel, :providerMessageId,
                    :participantId, :content, :receivedAt, :rawPayloadReference)
                on conflict (tenant_id, channel, provider_message_id) do nothing
                """)
                .param("tenantId", message.tenantId())
                .param("channel", message.channel().name())
                .param("providerMessageId", message.providerMessageId())
                .param("participantId", message.participantId())
                .param("content", message.text())
                .param("receivedAt", Timestamp.from(message.receivedAt()))
                .param("rawPayloadReference", message.rawPayloadReference())
                .update();
    }
}
