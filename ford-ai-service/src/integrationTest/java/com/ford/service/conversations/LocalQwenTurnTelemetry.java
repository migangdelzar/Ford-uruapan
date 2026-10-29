package com.ford.service.conversations;

import com.ford.service.observability.AiProviderCallObserver;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Writes allowlisted scalar telemetry; raw exchanges belong in the separate synthetic transcript. */
final class LocalQwenTurnTelemetry {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9-]{1,80}");

    private final Path path;
    private final ObjectMapper objectMapper = new ObjectMapper();

    LocalQwenTurnTelemetry(Path reportsDirectory, String runId) throws IOException {
        Objects.requireNonNull(reportsDirectory, "reports directory is required");
        if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("run ID must contain only letters, digits, and hyphens");
        }
        var root = reportsDirectory.toAbsolutePath().normalize();
        var directory = root.resolve("local-qwen").normalize();
        if (!directory.startsWith(root)) throw new IllegalArgumentException("telemetry directory escaped reports root");
        Files.createDirectories(directory);
        path = directory.resolve("turn-telemetry-" + runId + ".jsonl").normalize();
        if (!path.startsWith(directory)) throw new IllegalArgumentException("telemetry file escaped local report directory");
        Files.writeString(path, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    Path path() {
        return path;
    }

    void append(TurnRecord record) throws IOException {
        Objects.requireNonNull(record, "turn telemetry record is required");
        Files.writeString(path, objectMapper.writeValueAsString(record) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    record MemoryShape(int storedMessageCount, int durableFactCount, int summaryChars,
                       int activeMemoryItemCount, int memoryContextBytes) {
        MemoryShape {
            if (storedMessageCount < 0 || durableFactCount < 0 || summaryChars < 0
                    || activeMemoryItemCount < 0 || memoryContextBytes < 0) {
                throw new IllegalArgumentException("memory measurements must be nonnegative");
            }
        }
    }

    record TurnRecord(String runId, String scenarioId, int turnNumber, String snapshotLabel,
                      String snapshotSha256, String executionMode, String scenarioSha256, String checkpointId,
                      Integer checkpointTurn,
                      String modelId, String modelConfigId,
                      long wallTimeMillis, Long providerDurationMillis, int providerCallCount,
                      int retryCount, List<String> toolNames, int toolCallCount,
                      List<String> replayPrefetchToolNames, List<String> recordedSeedToolNames,
                      MemoryShape memoryShape, Integer promptTokens, Integer completionTokens,
                      AiProviderCallObserver.TokenUsageStatus tokenUsageStatus,
                      LocalQwenTranscriptWriter.ReplyOrigin replyOrigin, Outcome outcome) {
        TurnRecord {
            requireId(runId, "run ID");
            requireId(scenarioId, "scenario ID");
            if (turnNumber < 1 || wallTimeMillis < 0 || providerCallCount < 0 || retryCount < 0 || toolCallCount < 0) {
                throw new IllegalArgumentException("turn telemetry values must be nonnegative");
            }
            if (snapshotLabel == null || !List.of("oct22-baseline", "oct25-candidate").contains(snapshotLabel)) {
                throw new IllegalArgumentException("snapshot label is invalid");
            }
            if (snapshotSha256 == null || !snapshotSha256.matches("(?i)[a-f0-9]{64}")) {
                throw new IllegalArgumentException("snapshot manifest SHA-256 is invalid");
            }
            requireId(modelId, "model ID");
            requireId(modelConfigId, "model configuration ID");
            try {
                LocalQwenReplayExecutionMode.valueOf(executionMode);
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("execution mode is invalid", failure);
            }
            if (scenarioSha256 != null && !scenarioSha256.matches("(?i)[a-f0-9]{64}")) {
                throw new IllegalArgumentException("scenario hash is invalid");
            }
            if (LocalQwenReplayExecutionMode.CHECKPOINT_LIVE_WINDOW.name().equals(executionMode)) {
                if (checkpointId == null || !checkpointId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                        || scenarioSha256 == null
                        || !scenarioSha256.matches("(?i)[a-f0-9]{64}")
                        || checkpointTurn == null || checkpointTurn < 1 || checkpointTurn > 99) {
                    throw new IllegalArgumentException("checkpoint telemetry provenance is required");
                }
            } else if (checkpointId != null || checkpointTurn != null) {
                throw new IllegalArgumentException("checkpoint telemetry provenance is only valid in checkpoint mode");
            }
            toolNames = List.copyOf(toolNames == null ? List.of() : toolNames);
            replayPrefetchToolNames = List.copyOf(replayPrefetchToolNames == null ? List.of() : replayPrefetchToolNames);
            recordedSeedToolNames = List.copyOf(recordedSeedToolNames == null ? List.of() : recordedSeedToolNames);
            validateToolNames(toolNames);
            validateToolNames(replayPrefetchToolNames);
            validateToolNames(recordedSeedToolNames);
            if (toolCallCount != toolNames.size()) {
                throw new IllegalArgumentException("tool count must match recorded tool names");
            }
            Objects.requireNonNull(memoryShape, "memory shape is required");
            Objects.requireNonNull(tokenUsageStatus, "token usage status is required");
            Objects.requireNonNull(replyOrigin, "reply origin is required");
            Objects.requireNonNull(outcome, "turn outcome is required");
            if (providerDurationMillis != null && providerDurationMillis < 0) {
                throw new IllegalArgumentException("provider duration must be nonnegative");
            }
            if (promptTokens != null && promptTokens < 0 || completionTokens != null && completionTokens < 0) {
                throw new IllegalArgumentException("token counts must be nonnegative");
            }
            if (tokenUsageStatus == AiProviderCallObserver.TokenUsageStatus.ACTUAL
                    && (promptTokens == null || completionTokens == null)) {
                throw new IllegalArgumentException("actual usage requires both token counts");
            }
            if (tokenUsageStatus == AiProviderCallObserver.TokenUsageStatus.UNAVAILABLE
                    && (promptTokens != null || completionTokens != null)) {
                throw new IllegalArgumentException("unavailable usage must not contain token counts");
            }
        }

        static TurnRecord from(String runId, String scenarioId, int turnNumber,
                               LocalCatalogSnapshotSelection.Label snapshotLabel, String snapshotSha256,
                               String modelConfigId, long wallTimeMillis,
                               List<AiProviderCallObserver.ProviderCallMeasurement> calls,
                               List<LocalQwenTranscriptWriter.ToolTrace> tools, MemoryShape memoryShape,
                               Outcome outcome) {
            return from(runId, scenarioId, turnNumber, snapshotLabel, snapshotSha256, modelConfigId, wallTimeMillis,
                    calls, tools, memoryShape, LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN,
                    LocalQwenReplayExecutionMode.LIVE_DIAGNOSTIC, outcome);
        }

        static TurnRecord from(String runId, String scenarioId, int turnNumber,
                               LocalCatalogSnapshotSelection.Label snapshotLabel, String snapshotSha256,
                               String modelConfigId, long wallTimeMillis,
                               List<AiProviderCallObserver.ProviderCallMeasurement> calls,
                               List<LocalQwenTranscriptWriter.ToolTrace> tools, MemoryShape memoryShape,
                               LocalQwenTranscriptWriter.ReplyOrigin replyOrigin, Outcome outcome) {
            return from(runId, scenarioId, turnNumber, snapshotLabel, snapshotSha256, modelConfigId, wallTimeMillis,
                    calls, tools, memoryShape, replyOrigin, LocalQwenReplayExecutionMode.LIVE_DIAGNOSTIC, outcome);
        }

        static TurnRecord from(String runId, String scenarioId, int turnNumber,
                               LocalCatalogSnapshotSelection.Label snapshotLabel, String snapshotSha256,
                               String modelConfigId, long wallTimeMillis,
                               List<AiProviderCallObserver.ProviderCallMeasurement> calls,
                List<LocalQwenTranscriptWriter.ToolTrace> tools, MemoryShape memoryShape,
                LocalQwenTranscriptWriter.ReplyOrigin replyOrigin,
                LocalQwenReplayExecutionMode executionMode, Outcome outcome) {
            return from(runId, scenarioId, turnNumber, snapshotLabel, snapshotSha256, modelConfigId, wallTimeMillis,
                    calls, tools, memoryShape, replyOrigin, executionMode, null, null, null, outcome);
        }

        static TurnRecord from(String runId, String scenarioId, int turnNumber,
                               LocalCatalogSnapshotSelection.Label snapshotLabel, String snapshotSha256,
                               String modelConfigId, long wallTimeMillis,
                               List<AiProviderCallObserver.ProviderCallMeasurement> calls,
                                List<LocalQwenTranscriptWriter.ToolTrace> tools, MemoryShape memoryShape,
                               LocalQwenTranscriptWriter.ReplyOrigin replyOrigin,
                               LocalQwenReplayExecutionMode executionMode, String scenarioSha256,
                               String checkpointId, Integer checkpointTurn, Outcome outcome) {
            var safeCalls = List.copyOf(calls == null ? List.of() : calls);
            var safeTools = List.copyOf(tools == null ? List.of() : tools);
            if (replyOrigin == LocalQwenTranscriptWriter.ReplyOrigin.RECORDED_SEED
                    && (!safeCalls.isEmpty() || safeTools.stream().anyMatch(tool ->
                            tool.origin() != LocalQwenTranscriptWriter.ToolTraceOrigin.RECORDED_SEED))) {
                throw new IllegalArgumentException("seeded turns cannot include live calls or tools");
            }
            var commonStatus = commonUsageStatus(safeCalls);
            Integer prompt = null;
            Integer completion = null;
            if (commonStatus != AiProviderCallObserver.TokenUsageStatus.UNAVAILABLE && !safeCalls.isEmpty()) {
                prompt = safeCalls.stream().mapToInt(call -> call.promptTokens()).sum();
                completion = safeCalls.stream().mapToInt(call -> call.completionTokens()).sum();
            }
            var models = safeCalls.stream().map(AiProviderCallObserver.ProviderCallMeasurement::model)
                    .distinct().toList();
            var modelId = models.isEmpty() ? "unknown" : models.size() == 1 ? models.getFirst() : "mixed";
            var providerDuration = safeCalls.isEmpty() ? null
                    : safeCalls.stream().mapToLong(AiProviderCallObserver.ProviderCallMeasurement::durationMillis).sum();
            var retryCount = Math.toIntExact(safeCalls.stream()
                    .filter(call -> call.outcome() == AiProviderCallObserver.Outcome.FAILED).count());
            var maxMemoryBytes = safeCalls.stream()
                    .mapToInt(AiProviderCallObserver.ProviderCallMeasurement::memoryContextBytes).max()
                    .orElse(memoryShape.memoryContextBytes());
            var finalMemoryShape = new MemoryShape(memoryShape.storedMessageCount(), memoryShape.durableFactCount(),
                    memoryShape.summaryChars(), memoryShape.activeMemoryItemCount(), maxMemoryBytes);
            var toolNames = namesFor(safeTools, LocalQwenTranscriptWriter.ToolTraceOrigin.MODEL);
            var replayPrefetchToolNames = namesFor(safeTools,
                    LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH);
            var recordedSeedToolNames = namesFor(safeTools,
                    LocalQwenTranscriptWriter.ToolTraceOrigin.RECORDED_SEED);
            return new TurnRecord(runId, scenarioId, turnNumber, snapshotLabel.wireName(), snapshotSha256,
                    executionMode.name(), scenarioSha256, checkpointId, checkpointTurn,
                    modelId, modelConfigId, wallTimeMillis, providerDuration, safeCalls.size(), retryCount,
                    toolNames, toolNames.size(), replayPrefetchToolNames, recordedSeedToolNames,
                    finalMemoryShape, prompt, completion, commonStatus, replyOrigin, outcome);
        }

        private static List<String> namesFor(List<LocalQwenTranscriptWriter.ToolTrace> events,
                                             LocalQwenTranscriptWriter.ToolTraceOrigin origin) {
            return events.stream().filter(event -> event.origin() == origin)
                    .map(LocalQwenTranscriptWriter.ToolTrace::toolName).toList();
        }

        private static void validateToolNames(List<String> names) {
            if (names.stream().anyMatch(name -> name == null
                    || !name.matches("[A-Za-z0-9][A-Za-z0-9._:_-]{0,99}"))) {
                throw new IllegalArgumentException("tool names must use the safe identifier format");
            }
        }

        private static AiProviderCallObserver.TokenUsageStatus commonUsageStatus(
                List<AiProviderCallObserver.ProviderCallMeasurement> calls) {
            if (calls.isEmpty()) return AiProviderCallObserver.TokenUsageStatus.UNAVAILABLE;
            var status = calls.getFirst().tokenUsageStatus();
            if (status == AiProviderCallObserver.TokenUsageStatus.UNAVAILABLE
                    || calls.stream().anyMatch(call -> call.tokenUsageStatus() != status)) {
                return AiProviderCallObserver.TokenUsageStatus.UNAVAILABLE;
            }
            return status;
        }

        private static void requireId(String value, String label) {
            if (value == null || !SAFE_ID.matcher(value).matches()) {
                throw new IllegalArgumentException(label + " must use the safe identifier format");
            }
        }
    }

    enum Outcome { COMMITTED, FAILED }
}
