package com.ford.service.conversations.adapter.out;

import com.ford.service.conversations.application.ConversationMemoryRecallPort;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMemoryRecall;
import com.ford.service.conversations.domain.ConversationMessage;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable semantic recall for archived turns. Every query is scoped to the
 * complete conversation identity; this adapter never performs global recall.
 */
public final class PgVectorConversationMemoryRecallAdapter implements ConversationMemoryRecallPort {

    private static final int MAX_LIMIT = 3;

    private final JdbcClient jdbc;
    private final EmbeddingModel embeddingModel;

    public PgVectorConversationMemoryRecallAdapter(JdbcClient jdbc, EmbeddingModel embeddingModel) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
    }

    @Override
    public List<ConversationMemoryRecall> search(ConversationMemoryKey key, String query,
                                                  Instant before, int limit, double minimumSimilarity) {
        Objects.requireNonNull(key, "key");
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Objects.requireNonNull(before, "before");
        if (!Double.isFinite(minimumSimilarity) || minimumSimilarity < 0 || minimumSimilarity > 1) {
            throw new IllegalArgumentException("minimumSimilarity must be between 0 and 1");
        }
        var embedding = embeddingModel.embed(query);
        var boundedLimit = Math.min(validatePositive(limit, "limit"), MAX_LIMIT);
        return jdbc.sql("""
                select source_message_id, direction, content_redacted, occurred_at,
                       1 - (embedding <=> cast(:embedding as vector)) as similarity
                from conversation_memory_embedding
                where tenant_id = :tenantId
                  and conversation_id = :conversationId
                  and channel = :channel
                  and provider_account_id = :providerAccountId
                  and participant_id = :participantId
                  and active = true
                  and occurred_at < :before
                  and (1 - (embedding <=> cast(:embedding as vector))) >= :minimumSimilarity
                order by embedding <=> cast(:embedding as vector)
                limit :limit
                """)
                .param("tenantId", key.tenantId())
                .param("conversationId", key.conversationId())
                .param("channel", key.channel().name())
                .param("providerAccountId", key.providerAccountId())
                .param("participantId", key.participantId())
                .param("before", Timestamp.from(before))
                .param("embedding", vectorLiteral(embedding))
                .param("minimumSimilarity", minimumSimilarity)
                .param("limit", boundedLimit)
                .query((rs, rowNum) -> new ConversationMemoryRecall(
                        rs.getObject("source_message_id", UUID.class),
                        ConversationMessage.Direction.valueOf(rs.getString("direction")),
                        rs.getString("content_redacted"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getDouble("similarity")))
                .list();
    }

    @Override
    public void upsert(ConversationMemoryKey key, ConversationMessage message, String redactedContent,
                       String embeddingModelName, float[] embedding) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(message, "message");
        if (!key.conversationId().equals(message.conversationId())) {
            throw new IllegalArgumentException("message belongs to a different conversation");
        }
        if (redactedContent == null || redactedContent.isBlank()) {
            throw new IllegalArgumentException("redactedContent must not be blank");
        }
        if (embeddingModelName == null || embeddingModelName.isBlank()) {
            throw new IllegalArgumentException("embeddingModelName must not be blank");
        }
        var now = Timestamp.from(Instant.now());
        jdbc.sql("""
                insert into conversation_memory_embedding
                    (id, tenant_id, conversation_id, channel, provider_account_id,
                     participant_id, source_message_id, direction, content_redacted,
                     embedding_model, embedding, occurred_at, active, created_at, updated_at)
                values (:id, :tenantId, :conversationId, :channel, :providerAccountId,
                        :participantId, :sourceMessageId, :direction, :contentRedacted,
                        :embeddingModel, cast(:embedding as vector), :occurredAt, true,
                        :createdAt, :updatedAt)
                on conflict (conversation_id, source_message_id, embedding_model)
                do update set content_redacted = excluded.content_redacted,
                              embedding = excluded.embedding,
                              direction = excluded.direction,
                              occurred_at = excluded.occurred_at,
                              active = true,
                              updated_at = excluded.updated_at
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", key.tenantId())
                .param("conversationId", key.conversationId())
                .param("channel", key.channel().name())
                .param("providerAccountId", key.providerAccountId())
                .param("participantId", key.participantId())
                .param("sourceMessageId", message.id())
                .param("direction", message.direction().name())
                .param("contentRedacted", redactedContent)
                .param("embeddingModel", embeddingModelName)
                .param("embedding", vectorLiteral(embedding))
                .param("occurredAt", Timestamp.from(message.occurredAt()))
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }

    private static int validatePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
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
