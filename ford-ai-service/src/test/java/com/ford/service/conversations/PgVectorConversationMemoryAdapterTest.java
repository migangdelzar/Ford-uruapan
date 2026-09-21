package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.PgVectorConversationMemoryAdapter;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryFact;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PgVectorConversationMemoryAdapterTest {

    @Test
    void storesFactsInTheDedicatedConversationMemoryTable() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        var embedding = mock(EmbeddingModel.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        when(embedding.embed("Ranger")).thenReturn(new float[]{0.1f, 0.2f});

        var adapter = new PgVectorConversationMemoryAdapter(jdbc, embedding);
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "phone-1", "customer-1", UUID.randomUUID());
        adapter.upsert(key, new MemoryFact("preference", "Ranger", null));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains("conversation_memory_item");
        assertThat(sql.getValue()).contains("cast(:embedding as vector)");
    }
}
