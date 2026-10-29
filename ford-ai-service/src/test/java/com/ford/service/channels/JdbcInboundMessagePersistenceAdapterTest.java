package com.ford.service.channels;

import com.ford.service.channels.adapter.out.JdbcInboundMessagePersistenceAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.InboundAttachment;
import com.ford.service.channels.domain.InboundMessageKind;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

class JdbcInboundMessagePersistenceAdapterTest {

    @Test
    void writesTheInboundMessageToTheNormalizedTranscript() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<UUID> returnedId = mock();
        var canonicalConversationId = UUID.randomUUID();
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        when(statement.query(UUID.class)).thenReturn(returnedId);
        when(returnedId.single()).thenReturn(canonicalConversationId);

        var adapter = new JdbcInboundMessagePersistenceAdapter(jdbc, new ObjectMapper());
        var message = new InboundMessage(
                UUID.randomUUID(), Channel.WHATSAPP, "phone-1", "wamid.1", "customer-1", "Hola",
                List.of(), Instant.parse("2026-09-21T12:00:00Z"), "raw-1", UUID.randomUUID(), UUID.randomUUID());

        assertThat(adapter.persist(message, UUID.randomUUID()))
                .isEqualTo(canonicalConversationId);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce()).sql(sql.capture());
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains("conversation_message"));
        assertThat(sql.getAllValues()).anyMatch(value -> value.toLowerCase()
                .contains("on conflict (conversation_id, provider_message_id)"));
        assertThat(sql.getAllValues()).anyMatch(value -> value.toLowerCase().contains("returning id"));
    }

    @Test
    void persistsTransportKindAndOnlyOpaqueAttachmentMetadata() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<UUID> returnedId = mock();
        var conversationId = UUID.randomUUID();
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        when(statement.query(UUID.class)).thenReturn(returnedId);
        when(returnedId.single()).thenReturn(conversationId);
        var adapter = new JdbcInboundMessagePersistenceAdapter(jdbc, new ObjectMapper());
        var message = new InboundMessage(UUID.randomUUID(), Channel.WHATSAPP, "phone-1", "wamid.image",
                "customer-1", "Mire esta camioneta", List.of(new InboundAttachment("media-opaque-id",
                "image/jpeg", "truck.jpg", 1234L)), Instant.now(), "raw-ref", UUID.randomUUID(),
                UUID.randomUUID(), InboundMessageKind.IMAGE);

        adapter.persist(message, UUID.randomUUID());

        verify(jdbc, org.mockito.Mockito.atLeastOnce()).sql(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("message_kind") && sql.contains("attachment_metadata")));
        verify(statement).param(eq("messageKind"), eq("IMAGE"));
        var metadata = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(statement).param(eq("attachmentMetadata"), metadata.capture());
        assertThat(metadata.getValue().toString()).contains("media-opaque-id", "truck.jpg", "image/jpeg")
                .doesNotContain("https://", "access_token", "bytes");
    }
}
