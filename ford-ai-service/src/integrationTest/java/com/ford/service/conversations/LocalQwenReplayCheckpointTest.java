package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LocalQwenReplayCheckpointTest {
    private static final List<NaturalBuyerConversationScenario.Turn> SCENARIO = List.of(
            new NaturalBuyerConversationScenario.Turn(1, "Hola.", NaturalBuyerConversationScenario.Expectation.SOCIAL),
            new NaturalBuyerConversationScenario.Turn(2, "Busco pickup.", NaturalBuyerConversationScenario.Expectation.DISCOVERY));
    private static final String SNAPSHOT_HASH = "a".repeat(64);

    @Test
    void validatesScenarioSnapshotAndCheckpointPosition() {
        var checkpoint = validCheckpoint();

        checkpoint.validate(SCENARIO, LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE,
                SNAPSHOT_HASH, 1);

        assertThat(checkpoint.scenarioSha256()).isEqualTo(LocalQwenReplayCheckpoint.scenarioSha256(SCENARIO));
        assertThat(checkpoint.checkpointTurn()).isEqualTo(1);
    }

    @Test
    void rejectsScenarioOrSnapshotMismatch() {
        var checkpoint = validCheckpoint();
        assertThatIllegalArgumentException().isThrownBy(() -> checkpoint.validate(SCENARIO,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, "b".repeat(64), 1))
                .withMessageContaining("snapshot hash mismatch");
        assertThatIllegalArgumentException().isThrownBy(() -> checkpoint.validate(List.of(SCENARIO.get(1)),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, SNAPSHOT_HASH, 1))
                .withMessageContaining("scenario hash mismatch");
    }

    @Test
    void rejectsMalformedTurnHistoryAndNonSyntheticOrigin() {
        var checkpoint = validCheckpoint();
        var wrongRole = new LocalQwenReplayCheckpoint(checkpoint.schemaVersion(), checkpoint.checkpointId(),
                checkpoint.scenarioId(), checkpoint.scenarioSha256(), checkpoint.snapshotLabel(),
                checkpoint.snapshotSha256(), checkpoint.checkpointTurn(), checkpoint.providerAccountId(),
                checkpoint.participantId(), checkpoint.providerConversationId(), checkpoint.conversationVersion(),
                checkpoint.memoryVersion(), checkpoint.memorySummary(), checkpoint.memoryUpdatedAt(),
                checkpoint.contextStartedAt(), List.of(checkpoint.messages().get(1), checkpoint.messages().get(0)),
                checkpoint.durableFacts(), checkpoint.artifactOrigin());
        assertThatIllegalArgumentException().isThrownBy(() -> wrongRole.validate(SCENARIO,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, SNAPSHOT_HASH, 1))
                .withMessageContaining("ordered user/assistant pairs");

        var untrusted = new LocalQwenReplayCheckpoint(checkpoint.schemaVersion(), checkpoint.checkpointId(),
                checkpoint.scenarioId(), checkpoint.scenarioSha256(), checkpoint.snapshotLabel(),
                checkpoint.snapshotSha256(), checkpoint.checkpointTurn(), checkpoint.providerAccountId(),
                checkpoint.participantId(), checkpoint.providerConversationId(), checkpoint.conversationVersion(),
                checkpoint.memoryVersion(), checkpoint.memorySummary(), checkpoint.memoryUpdatedAt(),
                checkpoint.contextStartedAt(), checkpoint.messages(), checkpoint.durableFacts(), "CUSTOMER_EXPORT");
        assertThatIllegalArgumentException().isThrownBy(() -> untrusted.validate(SCENARIO,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, SNAPSHOT_HASH, 1))
                .withMessageContaining("artifact origin");
    }

    @Test
    void rejectsWrongCheckpointTurn() {
        assertThatIllegalArgumentException().isThrownBy(() -> validCheckpoint().validate(SCENARIO,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, SNAPSHOT_HASH, 2))
                .withMessageContaining("checkpoint turn mismatch");
    }

    @Test
    void rejectsExpiredMemoryBeforeDatabaseRestore() {
        assertThatIllegalArgumentException().isThrownBy(() -> validCheckpoint().validate(SCENARIO,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, SNAPSHOT_HASH, 1,
                java.time.Duration.ofSeconds(1)))
                .withMessageContaining("memory state is expired");
    }

    private static LocalQwenReplayCheckpoint validCheckpoint() {
        var time = Instant.parse("2026-10-27T12:00:00Z").toString();
        return new LocalQwenReplayCheckpoint(1, "natural-buyer-turn-001", "natural-buyer-100",
                LocalQwenReplayCheckpoint.scenarioSha256(SCENARIO), "oct25-candidate", SNAPSHOT_HASH, 1,
                "synthetic-account", "synthetic-participant", "synthetic-participant",
                1, 1, "Preferencia sintética de pickup.", time, "2026-10-27T11:00:00Z",
                List.of(new LocalQwenReplayCheckpoint.Message(1, "USER", "Hola.", time),
                        new LocalQwenReplayCheckpoint.Message(1, "ASSISTANT", "Hola, ¿en qué ayudo?", time)),
                List.of(new LocalQwenReplayCheckpoint.Fact("use_case", "synthetic family pickup")),
                "SYNTHETIC_FIXTURE");
    }
}
