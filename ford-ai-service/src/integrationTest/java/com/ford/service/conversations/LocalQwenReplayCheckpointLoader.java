package com.ford.service.conversations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Restores approved synthetic checkpoint state into the disposable integration-test database only. */
final class LocalQwenReplayCheckpointLoader {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    LocalQwenReplayCheckpointLoader(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
    }

    UUID restore(LocalQwenReplayCheckpoint checkpoint, UUID tenantId, String channel,
                 java.util.List<NaturalBuyerConversationScenario.Turn> scenario,
                 LocalCatalogSnapshotSelection.Label snapshotLabel, String snapshotHash,
                 int expectedCheckpointTurn, Duration memoryTtl) {
        java.util.Objects.requireNonNull(checkpoint, "checkpoint is required");
        java.util.Objects.requireNonNull(tenantId, "test tenant is required");
        if (!"WHATSAPP".equals(channel)) throw new IllegalArgumentException("unsupported checkpoint channel");
        checkpoint.validate(scenario, snapshotLabel, snapshotHash, expectedCheckpointTurn, memoryTtl);
        var conversationId = UUID.nameUUIDFromBytes((tenantId + ":" + checkpoint.checkpointId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var now = Timestamp.from(Instant.now());
        jdbc.sql("""
                insert into conversation
                    (id, tenant_id, channel, provider_conversation_id, state, version, created_at, updated_at,
                     provider_account_id, participant_id)
                values (:id, :tenant, :channel, :providerConversation, 'BOT_ACTIVE', :version, :now, :now,
                        :account, :participant)
                """)
                .param("id", conversationId).param("tenant", tenantId).param("channel", channel)
                .param("providerConversation", checkpoint.providerConversationId())
                .param("version", checkpoint.conversationVersion()).param("now", now)
                .param("account", checkpoint.providerAccountId()).param("participant", checkpoint.participantId())
                .update();

        var messageIds = new java.util.ArrayList<UUID>(checkpoint.messages().size());
        for (var message : checkpoint.messages()) {
            var messageId = UUID.nameUUIDFromBytes((checkpoint.checkpointId() + ":turn:" + message.turnNumber()
                    + ":" + message.role()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            messageIds.add(messageId);
            var direction = "USER".equals(message.role()) ? "INBOUND" : "OUTBOUND";
            var senderType = "USER".equals(message.role()) ? "CUSTOMER" : "BOT";
            var providerMessageId = "checkpoint:" + checkpoint.checkpointId() + ":" + message.turnNumber()
                    + ":" + message.role().toLowerCase(java.util.Locale.ROOT);
            jdbc.sql("""
                    insert into conversation_message
                        (id, conversation_id, direction, provider_message_id, content, received_at, sender_type)
                    values (:id, :conversation, :direction, :providerMessageId, :content, :receivedAt, :senderType)
                    """)
                    .param("id", messageId).param("conversation", conversationId).param("direction", direction)
                    .param("providerMessageId", providerMessageId).param("content", message.content())
                    .param("receivedAt", Timestamp.from(Instant.parse(message.occurredAt())))
                    .param("senderType", senderType).update();
        }

        var facts = checkpoint.durableFacts().stream().map(fact -> Map.of(
                "kind", fact.kind(), "content", fact.content())).toList();
        final String factsJson;
        try {
            factsJson = objectMapper.writeValueAsString(facts);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("checkpoint facts could not be serialized", failure);
        }
        jdbc.sql("""
                insert into conversation_memory
                    (conversation_id, tenant_id, channel, provider_account_id, participant_id, summary, facts_json,
                     memory_version, source_message_id, updated_at, context_started_at)
                values (:conversation, :tenant, :channel, :account, :participant, :summary, cast(:facts as jsonb),
                        :memoryVersion, :sourceMessageId, :updatedAt, :contextStartedAt)
                """)
                .param("conversation", conversationId).param("tenant", tenantId).param("channel", channel)
                .param("account", checkpoint.providerAccountId()).param("participant", checkpoint.participantId())
                .param("summary", checkpoint.memorySummary()).param("facts", factsJson)
                .param("memoryVersion", checkpoint.memoryVersion())
                .param("sourceMessageId", null)
                .param("updatedAt", Timestamp.from(Instant.parse(checkpoint.memoryUpdatedAt())))
                .param("contextStartedAt", Timestamp.from(Instant.parse(checkpoint.contextStartedAt())))
                .update();
        return conversationId;
    }
}
