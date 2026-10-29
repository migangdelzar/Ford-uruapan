package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.nio.file.Files;
import java.nio.file.Path;

/** Versioned synthetic state snapshot. Contains no live channel credentials or customer identifiers. */
record LocalQwenReplayCheckpoint(
        int schemaVersion,
        String checkpointId,
        String scenarioId,
        String scenarioSha256,
        String snapshotLabel,
        String snapshotSha256,
        int checkpointTurn,
        String providerAccountId,
        String participantId,
        String providerConversationId,
        long conversationVersion,
        int memoryVersion,
        String memorySummary,
        String memoryUpdatedAt,
        String contextStartedAt,
        List<Message> messages,
        List<Fact> durableFacts,
        String artifactOrigin) {

    LocalQwenReplayCheckpoint {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported checkpoint schema version");
        requireSafeId(checkpointId, "checkpoint ID");
        requireSafeId(scenarioId, "scenario ID");
        requireHash(scenarioSha256, "scenario hash");
        requireHash(snapshotSha256, "snapshot hash");
        if (snapshotLabel == null || !List.of("oct22-baseline", "oct25-candidate").contains(snapshotLabel)) {
            throw new IllegalArgumentException("checkpoint snapshot label is invalid");
        }
        if (checkpointTurn < 1 || conversationVersion < checkpointTurn || memoryVersion < 0) {
            throw new IllegalArgumentException("checkpoint turn or version is invalid");
        }
        requireSafeId(providerAccountId, "provider account ID");
        requireSafeId(participantId, "participant ID");
        requireSafeId(providerConversationId, "provider conversation ID");
        if (!participantId.equals(providerConversationId)) {
            throw new IllegalArgumentException("checkpoint participant and provider conversation must match");
        }
        if (!providerAccountId.startsWith("synthetic-") || !participantId.startsWith("synthetic-")) {
            throw new IllegalArgumentException("checkpoint identity must be synthetic");
        }
        if (memorySummary == null || memorySummary.length() > 12000) {
            throw new IllegalArgumentException("checkpoint memory summary is invalid");
        }
        requireInstant(memoryUpdatedAt, "memory updated at");
        requireInstant(contextStartedAt, "context started at");
        messages = List.copyOf(messages == null ? List.of() : messages);
        durableFacts = List.copyOf(durableFacts == null ? List.of() : durableFacts);
        if (messages.isEmpty() || messages.size() % 2 != 0) {
            throw new IllegalArgumentException("checkpoint must contain ordered user/assistant pairs");
        }
        Objects.requireNonNull(artifactOrigin, "artifact origin is required");
    }

    static LocalQwenReplayCheckpoint read(Path path, ObjectMapper objectMapper) {
        Objects.requireNonNull(path, "checkpoint path is required");
        Objects.requireNonNull(objectMapper, "object mapper is required");
        try (var input = Files.newInputStream(path)) {
            return objectMapper.readValue(input, LocalQwenReplayCheckpoint.class);
        } catch (IOException failure) {
            throw new IllegalArgumentException("checkpoint file could not be read", failure);
        }
    }

    void validate(List<NaturalBuyerConversationScenario.Turn> scenario,
                  LocalCatalogSnapshotSelection.Label expectedSnapshotLabel,
                  String expectedSnapshotHash, int expectedCheckpointTurn) {
        validate(scenario, expectedSnapshotLabel, expectedSnapshotHash, expectedCheckpointTurn, Duration.ofDays(90));
    }

    void validate(List<NaturalBuyerConversationScenario.Turn> scenario,
                  LocalCatalogSnapshotSelection.Label expectedSnapshotLabel,
                  String expectedSnapshotHash, int expectedCheckpointTurn, Duration memoryTtl) {
        Objects.requireNonNull(scenario, "scenario is required");
        Objects.requireNonNull(memoryTtl, "memory TTL is required");
        if (!List.of("SYNTHETIC_FIXTURE", "SEEDED_REPLAY_EXPORT", "LIVE_REPLAY_EXPORT")
                .contains(artifactOrigin)) {
            throw new IllegalArgumentException("checkpoint artifact origin is invalid");
        }
        if (!scenarioId.equals("natural-buyer-100") || !scenarioSha256.equals(scenarioSha256(scenario))) {
            throw new IllegalArgumentException("checkpoint scenario hash mismatch");
        }
        if (expectedSnapshotLabel == null || !snapshotLabel.equals(expectedSnapshotLabel.wireName())) {
            throw new IllegalArgumentException("checkpoint snapshot label mismatch");
        }
        requireHash(expectedSnapshotHash, "active snapshot hash");
        if (!snapshotSha256.equalsIgnoreCase(expectedSnapshotHash)) {
            throw new IllegalArgumentException("checkpoint snapshot hash mismatch");
        }
        var updatedAt = Instant.parse(memoryUpdatedAt);
        var contextStarted = Instant.parse(contextStartedAt);
        var now = Instant.now();
        if (memoryTtl.isZero() || memoryTtl.isNegative() || updatedAt.isBefore(now.minus(memoryTtl))
                || updatedAt.isAfter(now.plus(Duration.ofMinutes(5))) || contextStarted.isAfter(updatedAt)) {
            throw new IllegalArgumentException("checkpoint memory state is expired or inconsistent");
        }
        if (checkpointTurn != expectedCheckpointTurn || checkpointTurn >= scenario.size()) {
            throw new IllegalArgumentException("checkpoint turn mismatch");
        }
        var firstTurn = checkpointTurn - messages.size() / 2 + 1;
        if (firstTurn < 1) throw new IllegalArgumentException("checkpoint message window starts before turn one");
        var previousMessageAt = Instant.MIN;
        for (var index = 0; index < messages.size(); index += 2) {
            var turnNumber = firstTurn + index / 2;
            var userMessage = messages.get(index);
            var assistantMessage = messages.get(index + 1);
            var userMessageAt = Instant.parse(userMessage.occurredAt());
            var assistantMessageAt = Instant.parse(assistantMessage.occurredAt());
            if (turnNumber > checkpointTurn || messages.get(index).turnNumber() != turnNumber
                    || messages.get(index + 1).turnNumber() != turnNumber
                    || !"USER".equals(messages.get(index).role())
                    || !"ASSISTANT".equals(messages.get(index + 1).role())
                    || !scenario.get(turnNumber - 1).message().equals(userMessage.content())
                    || userMessageAt.isBefore(previousMessageAt) || assistantMessageAt.isBefore(userMessageAt)) {
                throw new IllegalArgumentException("checkpoint must contain ordered user/assistant pairs");
            }
            previousMessageAt = assistantMessageAt;
        }
        if (messages.getLast().turnNumber() != checkpointTurn) {
            throw new IllegalArgumentException("checkpoint message window must end at checkpoint turn");
        }
    }

    static String scenarioSha256(List<NaturalBuyerConversationScenario.Turn> scenario) {
        Objects.requireNonNull(scenario, "scenario is required");
        var canonical = new StringBuilder();
        for (var turn : scenario) {
            canonical.append(turn.number()).append('|').append(turn.expectation().name()).append('|')
                    .append(turn.message()).append('|')
                    .append(String.join(",", turn.expectedMemoryFacts())).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireSafeId(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private static void requireHash(String value, String label) {
        if (value == null || !value.matches("(?i)[a-f0-9]{64}")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private static void requireInstant(String value, String label) {
        try {
            Instant.parse(value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(label + " is invalid", failure);
        }
    }

    record Message(int turnNumber, String role, String content, String occurredAt) {
        Message {
            if (turnNumber < 1 || !List.of("USER", "ASSISTANT").contains(role)
                    || content == null || content.isBlank()) {
                throw new IllegalArgumentException("checkpoint message is invalid");
            }
            requireInstant(occurredAt, "message occurred at");
        }
    }

    record Fact(String kind, String content) {
        Fact {
            if (kind == null || !kind.matches("[a-z][a-z0-9_-]{0,59}")
                    || content == null || content.isBlank() || content.length() > 2000) {
                throw new IllegalArgumentException("checkpoint memory fact is invalid");
            }
        }
    }
}
