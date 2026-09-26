package com.ford.service.conversations;

import com.ford.service.conversations.adapter.out.JdbcAcceptedInboundTurnRepository;
import com.ford.service.channels.api.AcceptedInboundMessage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcAcceptedInboundTurnRepositoryTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void loadsMessageOnlyWhenTenantConversationAndReceiptAllMatch() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var query = mock(JdbcClient.MappedQuerySpec.class);
        var expected = new AcceptedInboundMessage("wamid.1", "participant-1", "¿Promociones?", Instant.now());
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(expected));
        var repository = new JdbcAcceptedInboundTurnRepository(jdbc);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();

        assertThat(repository.find(tenantId, conversationId, receiptId)).contains(expected);

        verify(jdbc, atLeastOnce()).sql(argThat(sql -> sql.contains("id = :receiptId")
                && sql.contains("tenant_id = :tenantId")
                && sql.contains("conversation_id = :conversationId")));
        verify(statement, atLeastOnce()).param("tenantId", tenantId);
        verify(statement, atLeastOnce()).param("conversationId", conversationId);
        verify(statement, atLeastOnce()).param("receiptId", receiptId);
    }
}
