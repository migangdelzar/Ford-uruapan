package com.ford.service.conversations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exports only verified synthetic replay state for a later live-window run. */
final class LocalQwenReplayCheckpointExporter {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    LocalQwenReplayCheckpointExporter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    Path export(Path reportsDirectory, String checkpointId, UUID tenantId, UUID conversationId,
                int checkpointTurn, List<NaturalBuyerConversationScenario.Turn> scenario,
                LocalCatalogSnapshotSelection.Label snapshotLabel, String snapshotHash,
                String artifactOrigin) throws IOException {
        if (artifactOrigin == null || !List.of("SEEDED_REPLAY_EXPORT", "LIVE_REPLAY_EXPORT").contains(artifactOrigin)) {
            throw new IllegalArgumentException("checkpoint export requires a synthetic replay origin");
        }
        var conversation = jdbc.sql("""
                select provider_account_id, participant_id, provider_conversation_id, channel, version
                  from conversation where id=:conversation and tenant_id=:tenant
                """)
                .param("conversation", conversationId).param("tenant", tenantId)
                .query((rs, row) -> new Conversation(rs.getString("provider_account_id"),
                        rs.getString("participant_id"), rs.getString("provider_conversation_id"),
                        rs.getString("channel"), rs.getLong("version"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("synthetic conversation was not found"));
        if (!"WHATSAPP".equals(conversation.channel()) || !conversation.providerAccountId().startsWith("synthetic-")
                || !conversation.participantId().startsWith("synthetic-")) {
            throw new IllegalArgumentException("checkpoint export accepts synthetic WhatsApp conversation only");
        }

        var projection = jdbc.sql("""
                select summary, facts_json, memory_version, updated_at, context_started_at,
                       context_started_at::text as context_started_at_text
                  from conversation_memory where conversation_id=:conversation and tenant_id=:tenant
                """)
                .param("conversation", conversationId).param("tenant", tenantId)
                .query((rs, row) -> new MemoryProjection(rs.getString("summary"), rs.getString("facts_json"),
                        rs.getInt("memory_version"), rs.getTimestamp("updated_at").toInstant(),
                        "-infinity".equals(rs.getString("context_started_at_text"))
                                ? Instant.EPOCH : rs.getTimestamp("context_started_at").toInstant()))
                .optional().orElseThrow(() -> new IllegalArgumentException("conversation memory is missing"));

        var rows = jdbc.sql("""
                select direction, content, received_at from conversation_message
                 where conversation_id=:conversation and received_at>=:contextStartedAt
                 order by received_at desc, case direction when 'INBOUND' then 0 else 1 end, id desc limit 20
                """)
                .param("conversation", conversationId)
                .param("contextStartedAt", Timestamp.from(projection.contextStartedAt()))
                .query((rs, row) -> new MessageRow(rs.getString("direction"), rs.getString("content"),
                        rs.getTimestamp("received_at").toInstant())).list();
        java.util.Collections.reverse(rows);
        if (rows.isEmpty() || rows.size() % 2 != 0) {
            throw new IllegalArgumentException("checkpoint message window must contain complete user/assistant pairs");
        }
        var firstTurn = checkpointTurn - rows.size() / 2 + 1;
        if (firstTurn < 1) throw new IllegalArgumentException("checkpoint message window precedes scenario start");
        var messages = new ArrayList<LocalQwenReplayCheckpoint.Message>(rows.size());
        for (var index = 0; index < rows.size(); index += 2) {
            var turnNumber = firstTurn + index / 2;
            var buyer = rows.get(index);
            var assistant = rows.get(index + 1);
            if (!"INBOUND".equals(buyer.direction()) || !"OUTBOUND".equals(assistant.direction())
                    || turnNumber > checkpointTurn || !scenario.get(turnNumber - 1).message().equals(buyer.content())) {
                throw new IllegalArgumentException("checkpoint history does not match synthetic scenario turns");
            }
            messages.add(new LocalQwenReplayCheckpoint.Message(turnNumber, "USER", buyer.content(),
                    buyer.occurredAt().toString()));
            messages.add(new LocalQwenReplayCheckpoint.Message(turnNumber, "ASSISTANT", assistant.content(),
                    assistant.occurredAt().toString()));
        }
        if (messages.getLast().turnNumber() != checkpointTurn) {
            throw new IllegalArgumentException("checkpoint message history does not reach requested turn");
        }

        var facts = new ArrayList<LocalQwenReplayCheckpoint.Fact>();
        try {
            JsonNode factNodes = objectMapper.readTree(projection.factsJson());
            if (!factNodes.isArray()) throw new IllegalArgumentException("checkpoint facts must be an array");
            for (var fact : factNodes) {
                facts.add(new LocalQwenReplayCheckpoint.Fact(fact.path("kind").asText(),
                        fact.path("content").asText()));
            }
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("checkpoint memory facts could not be parsed", failure);
        }
        var memorySummary = LocalQwenSeededChatCompletionPort.MEMORY_COMPACTION_SKIPPED.equals(projection.summary())
                ? "" : projection.summary();
        var checkpoint = new LocalQwenReplayCheckpoint(1, checkpointId, "natural-buyer-100",
                LocalQwenReplayCheckpoint.scenarioSha256(scenario), snapshotLabel.wireName(), snapshotHash,
                checkpointTurn, conversation.providerAccountId(), conversation.participantId(),
                conversation.providerConversationId(), conversation.version(), projection.memoryVersion(),
                memorySummary, projection.updatedAt().toString(),
                (projection.contextStartedAt().equals(Instant.EPOCH) ? messages.getFirst().occurredAt()
                        : projection.contextStartedAt()).toString(),
                messages, facts, artifactOrigin);
        checkpoint.validate(scenario, snapshotLabel, snapshotHash, checkpointTurn);

        var root = reportsDirectory.toAbsolutePath().normalize();
        var directory = root.resolve("local-qwen/checkpoints").normalize();
        var path = directory.resolve(checkpointId + ".json").normalize();
        if (!directory.startsWith(root) || !path.startsWith(directory)) {
            throw new IllegalArgumentException("checkpoint path escaped local report directory");
        }
        Files.createDirectories(directory);
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(checkpoint),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return path;
    }

    private record Conversation(String providerAccountId, String participantId, String providerConversationId,
                                String channel, long version) { }
    private record MemoryProjection(String summary, String factsJson, int memoryVersion,
                                    Instant updatedAt, Instant contextStartedAt) { }
    private record MessageRow(String direction, String content, Instant occurredAt) { }
}
