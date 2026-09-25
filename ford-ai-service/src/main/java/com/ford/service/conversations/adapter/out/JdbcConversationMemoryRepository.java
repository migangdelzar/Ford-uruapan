package com.ford.service.conversations.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryFact;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.MemoryUpdateResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcConversationMemoryRepository implements ConversationMemoryPort {

    private static final TypeReference<List<MemoryFact>> FACTS_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;

    public JdbcConversationMemoryRepository(JdbcClient jdbc,
                                            ObjectMapper objectMapper,
                                            MemoryProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public ConversationMemoryContext load(MemoryQuery query) {
        var projection = jdbc.sql("""
                select summary, facts_json, memory_version
                from conversation_memory
                where conversation_id = :conversationId
                  and tenant_id = :tenantId
                  and channel = :channel
                  and provider_account_id = :providerAccountId
                  and participant_id = :participantId
                """)
                .param("conversationId", query.key().conversationId())
                .param("tenantId", query.key().tenantId())
                .param("channel", query.key().channel().name())
                .param("providerAccountId", query.key().providerAccountId())
                .param("participantId", query.key().participantId())
                .query((rs, rowNum) -> new Projection(
                        rs.getString("summary"), rs.getString("facts_json"),
                        rs.getLong("memory_version")))
                .optional()
                .orElse(new Projection("", "[]", 0));

        var messages = jdbc.sql("""
                select id, conversation_id, direction, provider_message_id, content, received_at
                from (
                    select id, conversation_id, direction, provider_message_id, content, received_at,
                           row_number() over (order by received_at desc, id desc) as row_number
                    from conversation_message
                    where conversation_id = :conversationId
                      and (cast(:currentProviderMessageId as varchar) is null
                           or provider_message_id <> :currentProviderMessageId)
                ) recent
                where row_number <= :recentMessageLimit
                order by received_at asc, id asc
                """)
                .param("conversationId", query.key().conversationId())
                .param("currentProviderMessageId", query.currentProviderMessageId())
                .param("recentMessageLimit", query.recentMessageLimit())
                .query((rs, rowNum) -> new ConversationMessage(
                        rs.getObject("id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        ConversationMessage.Direction.valueOf(rs.getString("direction")),
                        rs.getString("provider_message_id"),
                        rs.getString("content"),
                        rs.getTimestamp("received_at").toInstant()))
                .list();

        return new ConversationMemoryContext(
                query.key(),
                truncate(projection.summary(), properties.summaryMaxChars()),
                messages,
                readFacts(projection.factsJson()),
                projection.memoryVersion());
    }

    @Override
    @Transactional
    public void recordInbound(ConversationMemoryKey key, ConversationMessage message) {
        recordMessage(key, message);
    }

    @Override
    @Transactional
    public MemoryUpdateResult updateProjection(ConversationMemoryKey key,
                                                MemoryProjectionUpdate update,
                                                long expectedVersion) {
        jdbc.sql("""
                insert into conversation_memory
                    (conversation_id, tenant_id, channel, provider_account_id, participant_id,
                     summary, facts_json, memory_version, source_message_id, updated_at)
                select :conversationId, :tenantId, :channel, :providerAccountId, :participantId,
                       '', '[]', 0, null, :updatedAt
                 where not exists (
                     select 1 from conversation_memory
                      where conversation_id = :conversationId
                 )
                """)
                .param("conversationId", key.conversationId())
                .param("tenantId", key.tenantId())
                .param("channel", key.channel().name())
                .param("providerAccountId", key.providerAccountId())
                .param("participantId", key.participantId())
                .param("updatedAt", Timestamp.from(Instant.now()))
                .update();

        var updated = jdbc.sql("""
                update conversation_memory
                set summary = :summary,
                    facts_json = cast(:factsJson as jsonb),
                    memory_version = memory_version + 1,
                    source_message_id = :sourceMessageId,
                    updated_at = :updatedAt
                where conversation_id = :conversationId
                  and tenant_id = :tenantId
                  and channel = :channel
                  and provider_account_id = :providerAccountId
                  and participant_id = :participantId
                  and memory_version = :expectedVersion
                """)
                .param("summary", truncate(update.summary(), properties.summaryMaxChars()))
                .param("factsJson", factsJson(update.durableFacts()))
                .param("sourceMessageId", update.sourceMessageId())
                .param("updatedAt", Timestamp.from(Instant.now()))
                .param("conversationId", key.conversationId())
                .param("tenantId", key.tenantId())
                .param("channel", key.channel().name())
                .param("providerAccountId", key.providerAccountId())
                .param("participantId", key.participantId())
                .param("expectedVersion", expectedVersion)
                .update();
        var currentVersion = jdbc.sql("""
                select memory_version from conversation_memory
                where conversation_id = :conversationId and tenant_id = :tenantId
                """)
                .param("conversationId", key.conversationId())
                .param("tenantId", key.tenantId())
                .query(Long.class)
                .single();
        return new MemoryUpdateResult(updated == 1, currentVersion);
    }

    private void recordMessage(ConversationMemoryKey key, ConversationMessage message) {
        jdbc.sql("""
                insert into conversation_message
                    (id, conversation_id, direction, provider_message_id, content, received_at)
                values (:id, :conversationId, :direction, :providerMessageId, :content, :receivedAt)
                on conflict (conversation_id, provider_message_id) do nothing
                """)
                .param("id", message.id())
                .param("conversationId", key.conversationId())
                .param("direction", message.direction().name())
                .param("providerMessageId", message.providerMessageId())
                .param("content", message.content())
                .param("receivedAt", Timestamp.from(message.occurredAt()))
                .update();
    }

    private List<MemoryFact> readFacts(String json) {
        try {
            return objectMapper.readValue(json == null ? "[]" : json, FACTS_TYPE).stream()
                    .limit(properties.factLimit())
                    .map(fact -> new MemoryFact(fact.kind(), truncate(fact.content(), properties.factMaxChars()),
                            fact.sourceMessageId()))
                    .toList();
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String factsJson(List<MemoryFact> facts) {
        try {
            return objectMapper.writeValueAsString(facts.stream()
                    .limit(properties.factLimit())
                    .map(fact -> new MemoryFact(fact.kind(), truncate(fact.content(), properties.factMaxChars()),
                            fact.sourceMessageId()))
                    .toList());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not serialize conversation memory facts", exception);
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private record Projection(String summary, String factsJson, long memoryVersion) {
    }
}
