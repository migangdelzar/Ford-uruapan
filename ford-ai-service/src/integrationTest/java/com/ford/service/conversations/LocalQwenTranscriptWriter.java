package com.ford.service.conversations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.regex.Pattern;

/** Writes only the explicitly synthetic user-visible exchange to a local test report. */
final class LocalQwenTranscriptWriter {
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9-]{1,80}");

    private final Path path;
    private final Path htmlPath;
    private final String runId;
    private final LocalCatalogSnapshotLoader.SnapshotSummary snapshot;
    private final LocalCatalogSnapshotSelection.Label snapshotLabel;
    private final int expectedTurns;
    private final int firstTurn;
    private final ReplayMode replayMode;
    private final LocalQwenReplayExecutionMode executionMode;
    private final java.util.List<LocalQwenTranscriptHtmlRenderer.TurnRecord> committedTurns = new java.util.ArrayList<>();
    private final java.util.Map<Integer, PrecisionByTurn> precisionByTurn = new java.util.LinkedHashMap<>();
    private int writtenTurns;
    private boolean failed;
    private boolean completed;
    private int seededPrefixTurns;
    private Integer handoffCount;
    private CheckpointMetadata checkpointMetadata;
    private String scenarioSha256;
    private boolean freshAcceptanceValidated;
    private LocalQwenTranscriptHtmlRenderer.FailedTurn failedTurn;

    LocalQwenTranscriptWriter(Path reportsDirectory, String runId,
                              LocalCatalogSnapshotLoader.SnapshotSummary snapshot) throws IOException {
        this(reportsDirectory, runId, snapshot, LocalCatalogSnapshotSelection.Label.OCT22_BASELINE, 100);
    }

    LocalQwenTranscriptWriter(Path reportsDirectory, String runId,
                              LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                              int expectedTurns) throws IOException {
        this(reportsDirectory, runId, snapshot, LocalCatalogSnapshotSelection.Label.OCT22_BASELINE,
                expectedTurns);
    }

    LocalQwenTranscriptWriter(Path reportsDirectory, String runId,
                              LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                              LocalCatalogSnapshotSelection.Label snapshotLabel,
                              int expectedTurns) throws IOException {
        this(reportsDirectory, runId, snapshot, snapshotLabel, expectedTurns, ReplayMode.RAW_QWEN);
    }

    LocalQwenTranscriptWriter(Path reportsDirectory, String runId,
                              LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                              LocalCatalogSnapshotSelection.Label snapshotLabel,
                              int expectedTurns, ReplayMode replayMode) throws IOException {
        this(reportsDirectory, runId, snapshot, snapshotLabel, expectedTurns, replayMode,
                LocalQwenReplayExecutionMode.LIVE_DIAGNOSTIC, 1);
    }

    LocalQwenTranscriptWriter(Path reportsDirectory, String runId,
                              LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                              LocalCatalogSnapshotSelection.Label snapshotLabel,
                              int expectedTurns, ReplayMode replayMode,
                              LocalQwenReplayExecutionMode executionMode) throws IOException {
        this(reportsDirectory, runId, snapshot, snapshotLabel, expectedTurns, replayMode, executionMode, 1);
    }

    LocalQwenTranscriptWriter(Path reportsDirectory, String runId,
                              LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                              LocalCatalogSnapshotSelection.Label snapshotLabel,
                              int expectedTurns, ReplayMode replayMode,
                              LocalQwenReplayExecutionMode executionMode, int firstTurn) throws IOException {
        Objects.requireNonNull(reportsDirectory, "reportsDirectory is required");
        Objects.requireNonNull(snapshot, "snapshot metadata is required");
        Objects.requireNonNull(snapshotLabel, "snapshot label is required");
        this.replayMode = Objects.requireNonNull(replayMode, "replay mode is required");
        this.executionMode = Objects.requireNonNull(executionMode, "execution mode is required");
        if (expectedTurns < 1 || expectedTurns > 100 || firstTurn < 1
                || firstTurn + expectedTurns - 1 > 100) {
            throw new IllegalArgumentException("expected turns must be between 1 and 100");
        }
        this.runId = runId;
        this.snapshot = snapshot;
        this.snapshotLabel = snapshotLabel;
        this.expectedTurns = expectedTurns;
        this.firstTurn = firstTurn;
        if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("run ID must contain only letters, digits, and hyphens");
        }
        var root = reportsDirectory.toAbsolutePath().normalize();
        var directory = root.resolve("local-qwen").normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("transcript directory must stay under reports directory");
        }
        Files.createDirectories(directory);
        var modePrefix = replayMode == ReplayMode.RAW_QWEN ? "" : "prefetched-qwen-";
        path = directory.resolve(expectedTurns + "-turn-" + modePrefix + "transcript-" + runId + ".md").normalize();
        htmlPath = path.resolveSibling(expectedTurns + "-turn-transcript-" + runId + ".html").normalize();
        if (!path.startsWith(directory)) {
            throw new IllegalArgumentException("transcript path must stay under local report directory");
        }
        Files.writeString(path, "# " + replayMode.status() + " — Local synthetic Qwen conversation\n\n"
                        + "Execution mode: " + executionMode.name() + "\n"
                        + "Mode: " + replayMode.status() + (replayMode == ReplayMode.RAW_QWEN
                        ? " (exploratory output; not safety or factuality verified)\n"
                        : " (local replay prefetch; Qwen authored response; not production)\n")
                        + "Run ID: " + runId + "\n"
                        + "Source: " + LocalCatalogSnapshotLoader.SOURCE_LABEL + "\n"
                        + "Snapshot label: " + snapshotLabel.wireName() + "\n"
                        + "Snapshot manifest SHA-256: " + snapshot.sha256() + "\n"
                        + "Snapshot refreshed at (UTC): " + snapshot.refreshedAt() + "\n"
                        + "Snapshot rows: vehicle variants: " + rowCount(snapshot, "vehicle-variants.csv")
                        + ", promotions: " + rowCount(snapshot, "promotions.csv")
                        + ", product vectors: " + rowCount(snapshot, "product-vectors.csv") + "\n"
                        + "Status: " + replayMode.status() + "\n\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        writeHtml();
    }

    private static int rowCount(LocalCatalogSnapshotLoader.SnapshotSummary snapshot, String fileName) {
        var count = snapshot.rowCounts().get(fileName);
        if (count == null || count < 1) {
            throw new IllegalArgumentException("required snapshot row count is missing: " + fileName);
        }
        return count;
    }

    Path path() {
        return path;
    }

    Path htmlPath() {
        return htmlPath;
    }

    void append(NaturalBuyerConversationScenario.Turn turn, String reply) throws IOException {
        append(turn, reply, java.util.List.of());
    }

    void append(NaturalBuyerConversationScenario.Turn turn, String reply, java.util.List<ToolTrace> toolEvents)
            throws IOException {
        append(turn, reply, toolEvents, ReplyOrigin.LIVE_QWEN);
    }

    void append(NaturalBuyerConversationScenario.Turn turn, String reply, java.util.List<ToolTrace> toolEvents,
                ReplyOrigin replyOrigin) throws IOException {
        Objects.requireNonNull(turn, "turn is required");
        Objects.requireNonNull(replyOrigin, "reply origin is required");
        var safeToolEvents = java.util.List.copyOf(toolEvents == null ? java.util.List.of() : toolEvents);
        if (replyOrigin == ReplyOrigin.RECORDED_SEED && safeToolEvents.stream()
                .anyMatch(event -> event.origin() != ToolTraceOrigin.RECORDED_SEED)) {
            throw new IllegalArgumentException("seed replies cannot carry live tool traces");
        }
        if (failed || completed) throw new IllegalStateException("transcript is already terminal");
        if (turn.number() != firstTurn + writtenTurns || turn.number() >= firstTurn + expectedTurns) {
            throw new IllegalArgumentException("transcript turns must be appended once and in order");
        }
        if (reply == null || reply.isBlank()) {
            throw new IllegalArgumentException("committed assistant reply must not be blank");
        }
        var section = "## Turn " + String.format(java.util.Locale.ROOT, "%03d", turn.number())
                + " — " + turn.expectation().name() + "\n\n"
                + "**Buyer**\n\n" + turn.message() + "\n\n"
                + "**Assistant**\n\n" + reply + "\n\n"
                + "**Reply origin:** " + replyOrigin.name() + "\n\n"
                + markdownTools(safeToolEvents);
        Files.writeString(path, section, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        committedTurns.add(new LocalQwenTranscriptHtmlRenderer.TurnRecord(turn.number(), turn.expectation().name(),
                turn.message(), reply, replyOrigin.name(), safeToolEvents));
        writtenTurns++;
        writeHtml();
    }

    void recordPrecision(NaturalBuyerConversationScenario.Turn turn,
                         LocalReplayPrecisionEvaluator.PrecisionResult catalog,
                         LocalReplayPrecisionEvaluator.PrecisionResult memory) throws IOException {
        Objects.requireNonNull(turn, "turn is required");
        if (replayMode != ReplayMode.PREFETCHED_QWEN) {
            throw new IllegalStateException("precision diagnostics are only recorded for prefetched replay");
        }
        if (committedTurns.stream().noneMatch(committed -> committed.number() == turn.number())) {
            throw new IllegalStateException("precision can be recorded only after its committed turn");
        }
        if (committedTurns.stream().filter(committed -> committed.number() == turn.number())
                .anyMatch(committed -> ReplyOrigin.RECORDED_SEED.name().equals(committed.replyOrigin()))) {
            throw new IllegalStateException("precision evidence cannot be recorded for RECORDED_SEED replies");
        }
        if (precisionByTurn.putIfAbsent(turn.number(), new PrecisionByTurn(catalog, memory)) != null) {
            throw new IllegalStateException("precision diagnostics can be recorded only once per turn");
        }
        var section = new StringBuilder("**Precision diagnostics**\n\n");
        if (catalog != null) section.append(formatPrecision("Catalog claim precision", catalog));
        if (memory != null) section.append(formatPrecision("Memory recall precision", memory));
        Files.writeString(path, section.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    void recordCheckpointMetadata(CheckpointMetadata metadata) throws IOException {
        Objects.requireNonNull(metadata, "checkpoint metadata is required");
        if (executionMode != LocalQwenReplayExecutionMode.CHECKPOINT_LIVE_WINDOW || writtenTurns != 0
                || checkpointMetadata != null) {
            throw new IllegalStateException("checkpoint metadata can be recorded once before a live window");
        }
        checkpointMetadata = metadata;
        recordScenarioSha256(metadata.scenarioSha256());
        Files.writeString(path, "Checkpoint fixture ID: " + metadata.checkpointId() + "\n"
                        + "Checkpoint turn: " + metadata.checkpointTurn() + "\n\n",
                StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        writeHtml();
    }

    void recordScenarioSha256(String hash) throws IOException {
        if (writtenTurns != 0 || scenarioSha256 != null || hash == null || !hash.matches("(?i)[a-f0-9]{64}")) {
            throw new IllegalArgumentException("scenario hash is invalid or already recorded");
        }
        scenarioSha256 = hash;
        Files.writeString(path, "Scenario SHA-256: " + hash + "\n\n", StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        writeHtml();
    }

    void recordHandoffCount(int count) throws IOException {
        if (count < 0 || completed || failed || handoffCount != null) {
            throw new IllegalArgumentException("handoff count is invalid or already recorded");
        }
        handoffCount = count;
        Files.writeString(path, "Handoff count: " + count + "\n\n", StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        writeHtml();
    }

    void recordFailure(NaturalBuyerConversationScenario.Turn turn, FailureCategory safeReason) throws IOException {
        recordFailure(turn, safeReason, java.util.List.of(), null);
    }

    void recordFailure(NaturalBuyerConversationScenario.Turn turn, FailureCategory safeReason,
                       java.util.List<ToolTrace> toolEvents) throws IOException {
        recordFailure(turn, safeReason, toolEvents, null);
    }

    void recordFailure(NaturalBuyerConversationScenario.Turn turn, FailureCategory safeReason,
                       java.util.List<ToolTrace> toolEvents,
                       LocalReplayPrecisionEvidence.ComparisonPrefetchStatus comparisonStatus) throws IOException {
        Objects.requireNonNull(turn, "failed turn is required");
        Objects.requireNonNull(safeReason, "safe failure category is required");
        var safeToolEvents = java.util.List.copyOf(toolEvents == null ? java.util.List.of() : toolEvents);
        var statusLine = comparisonStatus == null ? ""
                : "Comparison prefetch status: " + comparisonStatus.name() + "\n\n";
        if (failed || completed) throw new IllegalStateException("transcript is already terminal");
        appendPartialPrecisionSummary();
        if (turn.number() == firstTurn + writtenTurns - 1 && !committedTurns.isEmpty()) {
            var latestCommitted = committedTurns.get(committedTurns.size() - 1);
            if (latestCommitted.number() == turn.number() && latestCommitted.buyer().equals(turn.message())) {
                        Files.writeString(path, "## Validation failed after committed reply — turn " + turn.number()
                                + "\n\nReply committed; validation failed.\n\nFailed validation at turn: "
                                + turn.number() + "\n\nSafe reason: " + safeReason.name() + "\n\n" + statusLine
                                + markdownTools(safeToolEvents),
                        StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                failedTurn = new LocalQwenTranscriptHtmlRenderer.FailedTurn(turn.number(), turn.message(),
                        safeReason.name(), safeToolEvents, true);
                failed = true;
                writeHtml();
                return;
            }
        }
        if (turn.number() != firstTurn + writtenTurns || turn.number() >= firstTurn + expectedTurns) {
            throw new IllegalArgumentException("failure must identify the next uncommitted turn");
        }
        Files.writeString(path, "## Replay stopped — " + replayMode.status() + "\n\nFailed turn: " + turn.number()
                        + "\n\n**Buyer (reply not committed)**\n\n" + turn.message()
                        + "\n\nSafe reason: " + safeReason.name() + "\n\n" + statusLine
                        + markdownTools(safeToolEvents),
                StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        failedTurn = new LocalQwenTranscriptHtmlRenderer.FailedTurn(turn.number(), turn.message(),
                safeReason.name(), safeToolEvents, false);
        failed = true;
        writeHtml();
    }

    void complete() throws IOException {
        complete(0);
    }

    void complete(int seededPrefixTurns) throws IOException {
        if (failed || completed) throw new IllegalStateException("transcript is already terminal");
        if (executionMode == LocalQwenReplayExecutionMode.FRESH_FULL_LIVE && !freshAcceptanceValidated) {
            throw new IllegalStateException("fresh acceptance must pass all-live gates before completion");
        }
        if (writtenTurns != expectedTurns) {
            throw new IllegalStateException("transcript can complete only after all expected pairs are committed");
        }
        if (seededPrefixTurns < 0 || seededPrefixTurns > expectedTurns) {
            throw new IllegalArgumentException("seeded prefix must be within the expected turn range");
        }
        var actualSeededReplies = committedTurns.stream()
                .filter(turn -> turn.replyOrigin().equals(ReplyOrigin.RECORDED_SEED.name())).count();
        if (actualSeededReplies != seededPrefixTurns) {
            throw new IllegalArgumentException("seeded reply count does not match transcript origins");
        }
        this.seededPrefixTurns = seededPrefixTurns;
        appendAggregatePrecisionSummary(false);
        var completionText = executionMode == LocalQwenReplayExecutionMode.FRESH_FULL_LIVE
                ? "## Fresh all-live acceptance passed\n\n" + expectedTurns + "/" + expectedTurns
                        + " live Qwen turns; seeded replies: 0; handoffs: 0\n"
                : seededPrefixTurns == 0
                ? "## Complete — " + replayMode.status() + "\n\n" + expectedTurns + "/" + expectedTurns
                        + " turns complete; all replies are live Qwen\n"
                : "## Seeded replay complete — not the final all-live acceptance\n\n" + expectedTurns + "/"
                        + expectedTurns + " turns passed; RECORDED_SEED replies: " + seededPrefixTurns
                        + "; LIVE_QWEN turns: " + (expectedTurns - seededPrefixTurns) + "\n";
        Files.writeString(path, completionText,
                StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        completed = true;
        writeHtml();
    }

    void completeFreshFullLive() throws IOException {
        if (executionMode != LocalQwenReplayExecutionMode.FRESH_FULL_LIVE) {
            throw new IllegalStateException("fresh acceptance requires FRESH_FULL_LIVE execution mode");
        }
        if (expectedTurns != 100) {
            throw new IllegalStateException("fresh full-live acceptance requires exactly 100 turns");
        }
        if (committedTurns.size() != 100) {
            throw new IllegalStateException("fresh full-live acceptance requires exactly 100 committed turns");
        }
        if (committedTurns.stream().anyMatch(turn ->
                !ReplyOrigin.LIVE_QWEN.name().equals(turn.replyOrigin()))) {
            throw new IllegalStateException("fresh full-live acceptance requires all replies must be live Qwen");
        }
        if (handoffCount == null || handoffCount != 0) {
            throw new IllegalStateException("fresh full-live acceptance requires verified zero handoffs");
        }
        freshAcceptanceValidated = true;
        complete(0);
    }

    private void appendPartialPrecisionSummary() throws IOException {
        if (precisionByTurn.isEmpty()) return;
        appendAggregatePrecisionSummary(true);
    }

    private void appendAggregatePrecisionSummary(boolean partial) throws IOException {
        if (precisionByTurn.isEmpty()) return;
        var catalog = precisionByTurn.values().stream().map(PrecisionByTurn::catalog)
                .filter(Objects::nonNull).toList();
        var memory = precisionByTurn.values().stream().map(PrecisionByTurn::memory)
                .filter(Objects::nonNull).toList();
        var text = new StringBuilder("## Precision summary").append(partial ? " — PARTIAL" : "")
                .append("\n\nScored turns: ").append(precisionByTurn.size()).append(" / ")
                .append(expectedTurns).append("\n\n");
        if (!catalog.isEmpty()) text.append(formatAggregate("Catalog claim precision", catalog));
        if (!memory.isEmpty()) text.append(formatAggregate("Memory recall precision", memory));
        Files.writeString(path, text.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private static String formatPrecision(String label, LocalReplayPrecisionEvaluator.PrecisionResult result) {
        return label + ": supported=" + result.supportedClaims().size()
                + " unsupported=" + result.unsupportedClaims().size()
                + " unscored=" + result.unscoredClaims().size()
                + " precision=" + result.precision() + " coverage=" + result.coverage()
                + (result.recallMisses().isEmpty() ? "" : " recall misses=" + result.recallMisses().size()) + "\n\n";
    }

    private static String formatAggregate(String label,
                                          java.util.List<LocalReplayPrecisionEvaluator.PrecisionResult> results) {
        var supported = results.stream().mapToInt(result -> result.supportedClaims().size()).sum();
        var unsupported = results.stream().mapToInt(result -> result.unsupportedClaims().size()).sum();
        var unscored = results.stream().mapToInt(result -> result.unscoredClaims().size()).sum();
        var denominator = supported + unsupported;
        var coverageDenominator = denominator + unscored;
        var precision = denominator == 0 ? "N/A" : percent(supported, denominator);
        var coverage = coverageDenominator == 0 ? "N/A" : percent(denominator, coverageDenominator);
        var misses = results.stream().mapToInt(result -> result.recallMisses().size()).sum();
        return label + ": supported=" + supported + " / denominator=" + denominator
                + " unsupported=" + unsupported + " unscored=" + unscored
                + " precision=" + precision + " coverage=" + coverage
                + (label.startsWith("Memory") ? " recall misses=" + misses : "") + "\n\n";
    }

    private static String percent(int numerator, int denominator) {
        return Math.round(100.0 * numerator / denominator) + "%";
    }

    private record PrecisionByTurn(LocalReplayPrecisionEvaluator.PrecisionResult catalog,
                                   LocalReplayPrecisionEvaluator.PrecisionResult memory) { }

    private static String markdownTools(java.util.List<ToolTrace> toolEvents) {
        var section = new StringBuilder();
        appendMarkdownToolGroup(section, "Tools used", toolEvents.stream()
                .filter(event -> event.origin() == ToolTraceOrigin.MODEL).toList());
        var prefetched = toolEvents.stream().filter(event -> event.origin() == ToolTraceOrigin.REPLAY_PREFETCH).toList();
        if (!prefetched.isEmpty()) appendMarkdownToolGroup(section, "Replay prefetch", prefetched);
        var seeded = toolEvents.stream().filter(event -> event.origin() == ToolTraceOrigin.RECORDED_SEED).toList();
        if (!seeded.isEmpty()) appendMarkdownToolGroup(section, "Recorded seed tool evidence", seeded);
        return section.toString();
    }

    private static void appendMarkdownToolGroup(StringBuilder section, String label, java.util.List<ToolTrace> events) {
        section.append("**").append(label).append("**\n\n");
        if (events.isEmpty()) {
            section.append("No tool call recorded\n\n");
            return;
        }
        for (var event : events) {
            section.append("- `").append(event.toolName()).append("` — ").append(event.outcome());
            if (event.evidenceCount() != null) {
                section.append(" — ").append(event.evidenceCount())
                        .append(event.evidenceCount() == 1 ? " evidence item" : " evidence items");
            }
            section.append('\n');
        }
        section.append('\n');
    }

    private void writeHtml() throws IOException {
        var html = LocalQwenTranscriptHtmlRenderer.render(runId, snapshot, snapshotLabel, expectedTurns,
                replayMode, committedTurns, failedTurn, completed, seededPrefixTurns, executionMode,
                checkpointMetadata, handoffCount, scenarioSha256);
        Files.writeString(htmlPath, html, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    enum ToolTraceOrigin { MODEL, REPLAY_PREFETCH, RECORDED_SEED }
    enum ReplyOrigin { LIVE_QWEN, RECORDED_SEED }

    enum ReplayMode {
        RAW_QWEN("UNVERIFIED_RAW_QWEN"), PREFETCHED_QWEN("PREFETCHED_QWEN");

        private final String status;

        ReplayMode(String status) { this.status = status; }
        String status() { return status; }
    }

    record ToolTrace(String toolName, String outcome, Integer evidenceCount, ToolTraceOrigin origin) {
        ToolTrace(String toolName, String outcome, Integer evidenceCount) {
            this(toolName, outcome, evidenceCount, ToolTraceOrigin.MODEL);
        }

        ToolTrace {
            Objects.requireNonNull(origin, "tool trace origin is required");
            if (toolName == null || !toolName.matches("[A-Za-z][A-Za-z0-9_.:-]{0,79}")) {
                throw new IllegalArgumentException("invalid tool name");
            }
            if (outcome == null || !java.util.Set.of("started", "returned", "failed", "empty", "ambiguous")
                    .contains(outcome)) {
                throw new IllegalArgumentException("invalid tool outcome");
            }
            if (evidenceCount != null && evidenceCount < 0) {
                throw new IllegalArgumentException("evidence count must not be negative");
            }
        }
    }

    enum FailureCategory {
        SAFE_FALLBACK_REVIEW, TURN_TIMEOUT, INVALID_STATE, INVALID_EVIDENCE, UNSAFE_OUTCOME,
        MISSING_CATALOG_EVIDENCE
    }

    record CheckpointMetadata(String checkpointId, String scenarioSha256, int checkpointTurn) {
        CheckpointMetadata {
            if (checkpointId == null || !checkpointId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                    || scenarioSha256 == null || !scenarioSha256.matches("(?i)[a-f0-9]{64}")
                    || checkpointTurn < 1 || checkpointTurn > 99) {
                throw new IllegalArgumentException("checkpoint report metadata is invalid");
            }
        }
    }
}
