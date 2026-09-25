package com.ford.service.conversations.adapter.out;

import com.ford.service.conversations.application.ConversationMemorySemanticPort;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryFact;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stores durable semantic facts separately from the framework-managed knowledge vector store.
 * The table is scoped by the full conversation identity to prevent cross-tenant retrieval.
 */
public final class PgVectorConversationMemoryAdapter implements ConversationMemorySemanticPort {

    private final JdbcClient jdbc;
    private final EmbeddingModel embeddingModel;

    public PgVectorConversationMemoryAdapter(JdbcClient jdbc, EmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<MemoryFact> search(ConversationMemoryKey key, String query, int limit) {
        var embedding = embeddingModel.embed(query);
        return jdbc.sql("""
                select kind, content, source_message_id
                from conversation_memory_item
                where conversation_id = :conversationId
                  and tenant_id = :tenantId
                  and channel = :channel
                  and active = true
                order by embedding <=> cast(:embedding as vector)
                limit :limit
                """)
                .param("conversationId", key.conversationId())
                .param("tenantId", key.tenantId())
                .param("channel", key.channel().name())
                .param("embedding", vectorLiteral(embedding))
                .param("limit", limit)
                .query((rs, rowNum) -> new MemoryFact(
                        rs.getString("kind"),
                        rs.getString("content"),
                        rs.getObject("source_message_id", UUID.class)))
                .list();
    }

    @Override
    public void upsert(ConversationMemoryKey key, MemoryFact fact) {
        var embedding = embeddingModel.embed(fact.content());
        var now = Timestamp.from(Instant.now());
        jdbc.sql("""
                insert into conversation_memory_item
                    (id, conversation_id, tenant_id, channel, kind, content,
                     embedding, source_message_id, active, created_at, updated_at)
                values (:id, :conversationId, :tenantId, :channel, :kind, :content,
                        cast(:embedding as vector), :sourceMessageId, true, :createdAt, :updatedAt)
                on conflict (conversation_id, kind, content)
                do update set embedding = excluded.embedding,
                              source_message_id = excluded.source_message_id,
                              active = true,
                              updated_at = excluded.updated_at
                """)
                .param("id", UUID.randomUUID())
                .param("conversationId", key.conversationId())
                .param("tenantId", key.tenantId())
                .param("channel", key.channel().name())
                .param("kind", fact.kind())
                .param("content", fact.content())
                .param("embedding", vectorLiteral(embedding))
                .param("sourceMessageId", fact.sourceMessageId())
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }

    private static String vectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("Conversation memory embedding must not be empty");
        }
        var literal = new StringBuilder("[");
        for (var index = 0; index < embedding.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(embedding[index]);
        }
        return literal.append(']').toString();
    }
}
