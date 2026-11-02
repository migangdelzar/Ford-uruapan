package com.ford.service.conversations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Writes only synthetic client turns, committed MÍA replies, and persisted tool outcomes. */
final class DeepSeekReplayTranscriptWriter {

    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9-]{1,80}");
    private static final Pattern SAFE_TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,79}");

    private final Path path;
    private final int expectedTurns;
    private int writtenTurns;
    private boolean failed;
    private boolean completed;

    DeepSeekReplayTranscriptWriter(Path reportsRoot, String runId, int expectedTurns) throws IOException {
        Objects.requireNonNull(reportsRoot, "reports root is required");
        if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("run ID must contain only letters, digits, and hyphens");
        }
        if (expectedTurns < 1 || expectedTurns > 100) {
            throw new IllegalArgumentException("expected turns must be between 1 and 100");
        }
        this.expectedTurns = expectedTurns;

        var root = reportsRoot.toAbsolutePath().normalize();
        var directory = root.resolve("deepseek").normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("transcript directory must stay under reports root");
        }
        Files.createDirectories(directory);
        path = directory.resolve(expectedTurns + "-turn-transcript-" + runId + ".md").normalize();
        if (!path.startsWith(directory)) {
            throw new IllegalArgumentException("transcript path must stay under deepseek report directory");
        }
        Files.writeString(path, "# UNVERIFIED_RAW_DEEPSEEK — Synthetic buyer replay\n\n"
                        + "Status: exploratory output; factuality and safety not independently verified\n"
                        + "Run ID: " + runId + "\n"
                        + "Expected turns: " + expectedTurns + "\n\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    Path path() {
        return path;
    }

    void append(NaturalBuyerConversationScenario.Turn turn, String reply, List<ToolTrace> toolEvents)
            throws IOException {
        Objects.requireNonNull(turn, "turn is required");
        if (failed || completed) throw new IllegalStateException("transcript is already terminal");
        assertNextTurn(turn.number());
        if (reply == null || reply.isBlank()) {
            throw new IllegalArgumentException("committed assistant reply must not be blank");
        }

        var section = "## Turn " + String.format(java.util.Locale.ROOT, "%03d", turn.number())
                + " — " + turn.expectation().name() + "\n\n"
                + "**Client**\n\n" + turn.message() + "\n\n"
                + "**MÍA**\n\n" + reply + "\n\n"
                + renderTools(toolEvents);
        Files.writeString(path, section, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        writtenTurns++;
    }

    void recordFailure(NaturalBuyerConversationScenario.Turn turn, FailureCategory reason,
                       List<ToolTrace> toolEvents) throws IOException {
        Objects.requireNonNull(turn, "failed turn is required");
        Objects.requireNonNull(reason, "safe failure category is required");
        if (failed || completed) throw new IllegalStateException("transcript is already terminal");
        assertNextTurn(turn.number());

        var section = "## Replay stopped — UNVERIFIED_RAW_DEEPSEEK\n\n"
                + "Failed turn: " + turn.number() + "\n\n"
                + "**Client (no validated reply captured)**\n\n" + turn.message() + "\n\n"
                + "Safe reason: " + reason.name() + "\n\n"
                + renderTools(toolEvents);
        Files.writeString(path, section, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        failed = true;
    }

    void complete() throws IOException {
        if (failed || completed) throw new IllegalStateException("transcript is already terminal");
        if (writtenTurns != expectedTurns) {
            throw new IllegalStateException("transcript can complete only after all expected replies were written");
        }
        Files.writeString(path, "## Complete — UNVERIFIED_RAW_DEEPSEEK\n\n"
                        + expectedTurns + "/" + expectedTurns + " turns complete\n",
                StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        completed = true;
    }

    private void assertNextTurn(int turnNumber) {
        if (turnNumber != writtenTurns + 1 || turnNumber > expectedTurns) {
            throw new IllegalArgumentException("transcript turns must be appended once and in order");
        }
    }

    private static String renderTools(List<ToolTrace> toolEvents) {
        var safeEvents = List.copyOf(toolEvents == null ? List.of() : toolEvents);
        var section = new StringBuilder("**Tools used**\n\n");
        if (safeEvents.isEmpty()) return section.append("No tool call recorded\n\n").toString();
        for (var event : safeEvents) {
            section.append("- `").append(event.toolName()).append("` — ").append(event.outcome());
            if (event.evidenceCount() != null) {
                section.append(" — ").append(event.evidenceCount())
                        .append(event.evidenceCount() == 1 ? " evidence item" : " evidence items");
            }
            section.append('\n');
        }
        return section.append('\n').toString();
    }

    record ToolTrace(String toolName, String outcome, Integer evidenceCount) {
        ToolTrace {
            if (toolName == null || !SAFE_TOOL_NAME.matcher(toolName).matches()) {
                throw new IllegalArgumentException("invalid tool name");
            }
            if (!Set.of("returned", "failed", "empty").contains(outcome)) {
                throw new IllegalArgumentException("invalid persisted tool outcome");
            }
            if (evidenceCount != null && evidenceCount < 0) {
                throw new IllegalArgumentException("evidence count must not be negative");
            }
        }
    }

    enum FailureCategory {
        TURN_TIMEOUT, INVALID_STATE, ASSISTANT_UNAVAILABLE_RETRY,
        ASSISTANT_UNAVAILABLE_REVIEW, DATABASE_FAILURE, GRAPH_FAILURE
    }
}
