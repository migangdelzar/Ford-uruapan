package com.ford.service.conversations.adapter.out;

import com.ford.service.ai.application.ClassificationEvidence;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.conversations.application.ConversationDecisionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Repository
public class JdbcConversationDecisionRepository implements ConversationDecisionRepository {

    private final JdbcClient jdbc;

    public JdbcConversationDecisionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(UUID conversationId, String providerMessageId, ConversationDecision decision) {
        Objects.requireNonNull(conversationId, "conversation id is required");
        if (providerMessageId == null || providerMessageId.isBlank()) {
            throw new IllegalArgumentException("provider message id is required");
        }
        Objects.requireNonNull(decision, "conversation decision is required");
        var createdAt = Timestamp.from(Instant.now());
        for (var evidence : decision.evidence()) {
            saveEvidence(conversationId, providerMessageId, evidence, createdAt);
        }
    }

    private void saveEvidence(UUID conversationId, String providerMessageId,
                               ClassificationEvidence evidence, Timestamp createdAt) {
        jdbc.sql("""
                merge into conversation_decision as target
                using (values (:id, :conversationId, :providerMessageId, :classifierType,
                               :classification, :confidence, :reasonCode, :provider, :model,
                               :latencyMs, :fallbackUsed, cast(:createdAt as timestamp with time zone)))
                    as incoming(id, conversation_id, provider_message_id, classifier_type,
                                classification, confidence, reason_code, provider, model,
                                latency_ms, fallback_used, created_at)
                on target.conversation_id = incoming.conversation_id
                   and target.provider_message_id = incoming.provider_message_id
                   and target.classifier_type = incoming.classifier_type
                when matched then update set
                    classification = incoming.classification,
                    confidence = incoming.confidence,
                    reason_code = incoming.reason_code,
                    provider = incoming.provider,
                    model = incoming.model,
                    latency_ms = incoming.latency_ms,
                    fallback_used = incoming.fallback_used,
                    created_at = incoming.created_at
                when not matched then insert
                    (id, conversation_id, provider_message_id, classifier_type,
                     classification, confidence, reason_code, provider, model,
                     latency_ms, fallback_used, created_at)
                values (incoming.id, incoming.conversation_id, incoming.provider_message_id,
                        incoming.classifier_type, incoming.classification, incoming.confidence,
                        incoming.reason_code, incoming.provider, incoming.model,
                        incoming.latency_ms, incoming.fallback_used, incoming.created_at)
                """)
                .param("id", UUID.randomUUID())
                .param("conversationId", conversationId)
                .param("providerMessageId", providerMessageId)
                .param("classifierType", evidence.classifier().name())
                .param("classification", evidence.classification())
                .param("confidence", clamp(evidence.confidence()))
                .param("reasonCode", evidence.reasonCode())
                .param("provider", evidence.provider())
                .param("model", evidence.model())
                .param("latencyMs", evidence.latencyMs())
                .param("fallbackUsed", evidence.fallbackUsed())
                .param("createdAt", createdAt)
                .update();
    }

    private static double clamp(double confidence) {
        return Math.max(0.0, Math.min(1.0, confidence));
    }
}
