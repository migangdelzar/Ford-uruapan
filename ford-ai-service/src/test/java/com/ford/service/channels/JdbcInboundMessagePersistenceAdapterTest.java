package com.ford.service.channels;

import com.ford.service.channels.adapter.out.JdbcInboundMessagePersistenceAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcInboundMessagePersistenceAdapterTest {

    @Test
    void writesTheInboundMessageToTheNormalizedTranscript() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);

        var adapter = new JdbcInboundMessagePersistenceAdapter(jdbc);
        var message = new InboundMessage(
                UUID.randomUUID(), Channel.WHATSAPP, "phone-1", "wamid.1", "customer-1", "Hola",
                List.of(), Instant.parse("2026-09-21T12:00:00Z"), "raw-1", UUID.randomUUID(), UUID.randomUUID());

        adapter.persist(message, UUID.randomUUID());

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce()).sql(sql.capture());
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains("conversation_message"));
        assertThat(sql.getAllValues()).anyMatch(value -> value.toLowerCase()
                .contains("on conflict (conversation_id, provider_message_id)"));
    }
}
