package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.PgVectorConversationMemoryRecallAdapter;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgVectorConversationMemoryRecallAdapterTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void searchScopesByFullConversationIdentityAndCapsResults() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var query = mock(JdbcClient.MappedQuerySpec.class);
        var embedding = mock(EmbeddingModel.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.list()).thenReturn(java.util.List.of());
        when(embedding.embed("budget")).thenReturn(new float[]{0.1f, 0.2f});

        var adapter = new PgVectorConversationMemoryRecallAdapter(jdbc, embedding);
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "provider-1", "participant-1", UUID.randomUUID());

        adapter.search(key, "budget", Instant.now(), 10, 0.7);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains("conversation_memory_embedding")
                .contains("tenant_id = :tenantId")
                .contains("conversation_id = :conversationId")
                .contains("provider_account_id = :providerAccountId")
                .contains("participant_id = :participantId")
                .contains("active = true")
                .contains("occurred_at < :before")
                .contains("embedding <=> cast(:embedding as vector)")
                .contains("limit :limit");
        verify(statement).param("limit", 3);
    }

    @Test
    void upsertUsesSourceMessageAndEmbeddingModelAsIdempotencyKey() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        var embedding = mock(EmbeddingModel.class);
        var adapter = new PgVectorConversationMemoryRecallAdapter(jdbc, embedding);
        var conversationId = UUID.randomUUID();
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "provider-1", "participant-1", conversationId);
        var message = new ConversationMessage(UUID.randomUUID(), conversationId,
                ConversationMessage.Direction.INBOUND, "wamid.1", "I like Ranger", Instant.now());

        adapter.upsert(key, message, "I like Ranger", "text-embedding-3-small", new float[]{0.1f, 0.2f});

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains("on conflict (conversation_id, source_message_id, embedding_model)")
                .contains("cast(:embedding as vector)");
    }
}
