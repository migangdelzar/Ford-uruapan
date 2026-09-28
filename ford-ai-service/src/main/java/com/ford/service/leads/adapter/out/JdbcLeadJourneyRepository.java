package com.ford.service.leads.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.leads.application.LeadJourneyRepository;
import com.ford.service.leads.domain.LeadJourneyFact;
import com.ford.service.leads.domain.LeadJourneyFactKey;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.leads.domain.LeadJourneyTopic;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcLeadJourneyRepository implements LeadJourneyRepository {

    private static final TypeReference<Set<LeadJourneyTopic>> TOPICS = new TypeReference<>() { };
    private static final TypeReference<Map<LeadJourneyFactKey, LeadJourneyFact>> FACTS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcLeadJourneyRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public LeadJourneySnapshot getOrCreate(UUID tenantId, UUID conversationId) {
        jdbc.sql("""
                insert into lead_journey (tenant_id, conversation_id, lead_id, visited_topics, paused_topics,
                                          facts, pending_question_attempts, version, updated_at)
                select c.tenant_id, c.id,
                       (select l.id from lead l where l.tenant_id = c.tenant_id and l.conversation_id = c.id
                        order by l.created_at limit 1),
                       '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 0, 0, now()
                from conversation c
                where c.id = :conversationId and c.tenant_id = :tenantId
                on conflict (conversation_id) do nothing
                """)
                .param("conversationId", conversationId).param("tenantId", tenantId).update();

        return jdbc.sql("""
                select tenant_id, conversation_id, lead_id, current_topic, visited_topics, paused_topics,
                       facts, pending_question_key, pending_question_attempts, last_processed_message_id,
                       version, updated_at
                from lead_journey
                where tenant_id = :tenantId and conversation_id = :conversationId
                """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query((rs, row) -> new LeadJourneySnapshot(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("conversation_id", UUID.class),
                        rs.getObject("lead_id", UUID.class), topic(rs.getString("current_topic")),
                        read(rs.getString("visited_topics"), TOPICS), read(rs.getString("paused_topics"), TOPICS),
                        read(rs.getString("facts"), FACTS), rs.getString("pending_question_key"),
                        rs.getInt("pending_question_attempts"), rs.getString("last_processed_message_id"),
                        rs.getLong("version"), rs.getTimestamp("updated_at").toInstant()))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("conversation is not available in tenant scope"));
    }

    @Override
    public boolean update(UUID tenantId, UUID conversationId, long expectedVersion,
                          LeadJourneySnapshot snapshot, String providerMessageId) {
        if (!tenantId.equals(snapshot.tenantId()) || !conversationId.equals(snapshot.conversationId())
                || providerMessageId == null || providerMessageId.isBlank()) {
            return false;
        }
        return jdbc.sql("""
                update lead_journey
                   set current_topic = :currentTopic,
                       visited_topics = cast(:visitedTopics as jsonb),
                       paused_topics = cast(:pausedTopics as jsonb),
                       facts = cast(:facts as jsonb),
                       pending_question_key = :pendingQuestionKey,
                       pending_question_attempts = :pendingQuestionAttempts,
                       last_processed_message_id = :providerMessageId,
                       version = version + 1,
                       updated_at = :updatedAt
                 where tenant_id = :tenantId and conversation_id = :conversationId
                   and version = :expectedVersion
                   and last_processed_message_id is distinct from :providerMessageId
                """)
                .param("currentTopic", snapshot.currentTopic() == null ? null : snapshot.currentTopic().name())
                .param("visitedTopics", write(snapshot.visitedTopics()))
                .param("pausedTopics", write(snapshot.pausedTopics()))
                .param("facts", write(snapshot.facts()))
                .param("pendingQuestionKey", snapshot.pendingQuestionKey())
                .param("pendingQuestionAttempts", snapshot.pendingQuestionAttempts())
                .param("providerMessageId", providerMessageId)
                .param("updatedAt", Timestamp.from(snapshot.updatedAt() == null ? Instant.now() : snapshot.updatedAt()))
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize lead journey", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not deserialize lead journey", exception);
        }
    }

    private static LeadJourneyTopic topic(String value) {
        return value == null ? null : LeadJourneyTopic.valueOf(value);
    }
}
