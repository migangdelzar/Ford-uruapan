package com.ford.service.conversations;

import com.ford.service.observability.AiProviderCallObserver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LocalQwenTurnTelemetryTest {
    @TempDir Path tempDir;

    @Test
    void writesOnlyAllowlistedTurnMeasurementsAndKeepsActualTokenProvenance() throws Exception {
        var writer = new LocalQwenTurnTelemetry(tempDir, "run-safe");
        var record = LocalQwenTurnTelemetry.TurnRecord.from("run-safe", "f250-xlt-powertrain", 1,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, "a".repeat(64), "qwen3.5:4b", 120,
                List.of(actualCall(11, 101, 21, 80), actualCall(12, 13, 5, 40)),
                List.of(new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 2)),
                new LocalQwenTurnTelemetry.MemoryShape(4, 2, 14, 1, 900),
                LocalQwenTurnTelemetry.Outcome.COMMITTED);

        writer.append(record);

        var jsonl = Files.readString(writer.path());
        var row = new ObjectMapper().readTree(jsonl.lines().findFirst().orElseThrow());
        assertThat(row.path("providerCallCount").asInt()).isEqualTo(2);
        assertThat(row.path("retryCount").asInt()).isZero();
        assertThat(row.path("promptTokens").asInt()).isEqualTo(114);
        assertThat(row.path("completionTokens").asInt()).isEqualTo(26);
        assertThat(row.path("tokenUsageStatus").asText()).isEqualTo("ACTUAL");
        assertThat(row.path("executionMode").asText()).isEqualTo("LIVE_DIAGNOSTIC");
        assertThat(row.path("toolNames").get(0).asText()).isEqualTo("get_vehicle");
        assertThat(row.path("toolCallCount").asInt()).isEqualTo(1);
        assertThat(row.path("providerDurationMillis").asLong()).isEqualTo(23);
        assertThat(row.path("memoryShape").path("memoryContextBytes").asInt()).isEqualTo(80);
        assertThat(jsonl).doesNotContain("SYNTHETIC_PROMPT_SENTINEL", "SYNTHETIC_ANSWER_SENTINEL",
                "memory content", "toolArguments", "contact@example.invalid");
    }

    @Test
    void reportsMissingProviderUsageAsUnavailableInsteadOfZero() {
        var record = LocalQwenTurnTelemetry.TurnRecord.from("run-safe", "unsupported-fact-abstention", 1,
                LocalCatalogSnapshotSelection.Label.OCT22_BASELINE, "b".repeat(64), "qwen3.5:4b", 60,
                List.of(unavailableCall()), List.of(),
                new LocalQwenTurnTelemetry.MemoryShape(1, 0, 0, 0, 0),
                LocalQwenTurnTelemetry.Outcome.COMMITTED);

        assertThat(record.promptTokens()).isNull();
        assertThat(record.completionTokens()).isNull();
        assertThat(record.tokenUsageStatus()).isEqualTo(AiProviderCallObserver.TokenUsageStatus.UNAVAILABLE);
    }

    @Test
    void recordsSeedOriginWithoutClaimingAProviderCall() throws Exception {
        var writer = new LocalQwenTurnTelemetry(tempDir, "run-seed");
        var record = LocalQwenTurnTelemetry.TurnRecord.from("run-seed", "natural-buyer-100", 1,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, "d".repeat(64), "qwen3.5:4b", 25,
                List.of(), List.of(new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 2,
                                LocalQwenTranscriptWriter.ToolTraceOrigin.RECORDED_SEED)),
                new LocalQwenTurnTelemetry.MemoryShape(1, 0, 0, 0, 0),
                LocalQwenTranscriptWriter.ReplyOrigin.RECORDED_SEED,
                LocalQwenTurnTelemetry.Outcome.COMMITTED);

        writer.append(record);

        var row = new ObjectMapper().readTree(Files.readString(writer.path()).lines().findFirst().orElseThrow());
        assertThat(row.path("replyOrigin").asText()).isEqualTo("RECORDED_SEED");
        assertThat(row.path("executionMode").asText()).isEqualTo("LIVE_DIAGNOSTIC");
        assertThat(row.path("providerCallCount").asInt()).isZero();
        assertThat(row.path("toolNames")).isEmpty();
        assertThat(row.path("toolCallCount").asInt()).isZero();
        assertThat(row.path("replayPrefetchToolNames")).isEmpty();
        assertThat(row.path("recordedSeedToolNames").get(0).asText()).isEqualTo("get_vehicle");
        assertThat(row.path("tokenUsageStatus").asText()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void rejectsLiveCallsOrCatalogEvidenceOnSeededTelemetry() {
        assertThatIllegalArgumentException().isThrownBy(() -> LocalQwenTurnTelemetry.TurnRecord.from(
                "run-seed-live-call", "natural-buyer-100", 1,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, "d".repeat(64), "qwen3.5:4b", 25,
                List.of(actualCall(10, 20, 3, 8)), List.of(),
                new LocalQwenTurnTelemetry.MemoryShape(1, 0, 0, 0, 0),
                LocalQwenTranscriptWriter.ReplyOrigin.RECORDED_SEED,
                LocalQwenReplayExecutionMode.SEEDED_DIAGNOSTIC,
                null, null, null, LocalQwenTurnTelemetry.Outcome.COMMITTED))
                .withMessageContaining("seeded turns cannot include live calls or tools");

        assertThatIllegalArgumentException().isThrownBy(() -> LocalQwenTurnTelemetry.TurnRecord.from(
                "run-seed-prefetch", "natural-buyer-100", 1,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, "d".repeat(64), "qwen3.5:4b", 25,
                List.of(), List.of(new LocalQwenTranscriptWriter.ToolTrace("search_vehicles", "returned", 1,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH)),
                new LocalQwenTurnTelemetry.MemoryShape(1, 0, 0, 0, 0),
                LocalQwenTranscriptWriter.ReplyOrigin.RECORDED_SEED,
                LocalQwenReplayExecutionMode.SEEDED_DIAGNOSTIC,
                null, null, null, LocalQwenTurnTelemetry.Outcome.COMMITTED))
                .withMessageContaining("seeded turns cannot include live calls or tools");
    }

    @Test
    void recordsCheckpointFixtureIdentityOnLiveWindowTurns() {
        var record = LocalQwenTurnTelemetry.TurnRecord.from("run-checkpoint", "checkpoint-window", 86,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, "e".repeat(64), "qwen3.5:4b", 40,
                List.of(actualCall(10, 100, 10, 50)), List.of(),
                new LocalQwenTurnTelemetry.MemoryShape(20, 3, 90, 0, 50),
                LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN,
                LocalQwenReplayExecutionMode.CHECKPOINT_LIVE_WINDOW,
                "f".repeat(64), "natural-buyer-turn-085", 85,
                LocalQwenTurnTelemetry.Outcome.COMMITTED);

        assertThat(record.executionMode()).isEqualTo("CHECKPOINT_LIVE_WINDOW");
        assertThat(record.checkpointId()).isEqualTo("natural-buyer-turn-085");
        assertThat(record.scenarioSha256()).isEqualTo("f".repeat(64));
        assertThat(record.checkpointTurn()).isEqualTo(85);
    }

    @Test
    void countsFailedProviderCallsAsRetriesAndDoesNotInventAggregateTokenTotals() {
        var record = LocalQwenTurnTelemetry.TurnRecord.from("run-safe", "ranger-discovery-2027", 1,
                LocalCatalogSnapshotSelection.Label.OCT22_BASELINE, "c".repeat(64), "qwen3.5:4b", 90,
                List.of(failedCall(), actualCall(11, 101, 21, 20)), List.of(),
                new LocalQwenTurnTelemetry.MemoryShape(1, 0, 0, 0, 20),
                LocalQwenTurnTelemetry.Outcome.COMMITTED);

        assertThat(record.retryCount()).isEqualTo(1);
        assertThat(record.promptTokens()).isNull();
        assertThat(record.completionTokens()).isNull();
        assertThat(record.tokenUsageStatus()).isEqualTo(AiProviderCallObserver.TokenUsageStatus.UNAVAILABLE);
    }

    @Test
    void rejectsUnsafeRunAndScenarioIdentifiers() throws Exception {
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalQwenTurnTelemetry(tempDir, "../outside"));
        assertThatIllegalArgumentException().isThrownBy(() -> LocalQwenTurnTelemetry.TurnRecord.from(
                "run-safe", "../../bad", 1, LocalCatalogSnapshotSelection.Label.OCT22_BASELINE,
                "hash", "qwen3.5:4b", 1, List.of(), List.of(),
                new LocalQwenTurnTelemetry.MemoryShape(0, 0, 0, 0, 0),
                LocalQwenTurnTelemetry.Outcome.FAILED));
    }

    private static AiProviderCallObserver.ProviderCallMeasurement actualCall(long duration,
                                                                              int promptTokens,
                                                                              int completionTokens,
                                                                              int memoryBytes) {
        return new AiProviderCallObserver.ProviderCallMeasurement("ollama", "qwen3.5:4b", duration,
                promptTokens, completionTokens, AiProviderCallObserver.TokenUsageStatus.ACTUAL,
                1, memoryBytes, false, AiProviderCallObserver.Outcome.RETURNED);
    }

    private static AiProviderCallObserver.ProviderCallMeasurement unavailableCall() {
        return new AiProviderCallObserver.ProviderCallMeasurement("ollama", "qwen3.5:4b", 12,
                null, null, AiProviderCallObserver.TokenUsageStatus.UNAVAILABLE,
                0, 0, false, AiProviderCallObserver.Outcome.RETURNED);
    }

    private static AiProviderCallObserver.ProviderCallMeasurement failedCall() {
        return new AiProviderCallObserver.ProviderCallMeasurement("ollama", "qwen3.5:4b", 30,
                null, null, AiProviderCallObserver.TokenUsageStatus.UNAVAILABLE,
                0, 0, false, AiProviderCallObserver.Outcome.FAILED);
    }
}
