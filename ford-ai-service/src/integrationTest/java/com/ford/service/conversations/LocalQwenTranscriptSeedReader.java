package com.ford.service.conversations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only a contiguous, terminally validated prefix from a local synthetic replay transcript. */
final class LocalQwenTranscriptSeedReader {
    private static final Pattern TURN_HEADER = Pattern.compile("(?m)^## Turn (\\d{3}) — [^\\r\\n]+\\R");
    private static final Pattern FAILED_TURN = Pattern.compile(
            "(?m)^## Validation failed after committed reply — turn (\\d+)\\s*$");
    private static final Pattern STOPPED_TURN = Pattern.compile("(?m)^Failed turn: (\\d+)\\s*$");
    private static final Pattern TERMINAL = Pattern.compile("(?m)^## (?:Complete|Checkpoint|Validation failed|Replay stopped)");
    private static final Pattern SNAPSHOT_HASH = Pattern.compile(
            "(?m)^Snapshot manifest SHA-256: ([a-fA-F0-9]{64})\\s*$");
    private static final Pattern TOOL_TRACE = Pattern.compile(
            "^- `([A-Za-z][A-Za-z0-9_.:-]{0,79})` — (started|returned|failed|empty|ambiguous)"
                    + "(?: — (\\d+) evidence items?)?$");
    private static final Set<String> SAFE_TOOL_NAMES = Set.of(
            "search_vehicles", "get_vehicle", "search_offers", "search_vehicle_and_offers", "get_page_links",
            "get_vehicle_provenance", "get_catalog_status", "compare_vehicles", "compare_trims",
            "compare_model_years", "get_variant_comparisons", "list_promotions", "vehicle_snapshot_search",
            "finance_estimate", "list_finance_products", "approved_knowledge_search");

    private LocalQwenTranscriptSeedReader() { }

    static List<SeedReply> read(Path transcript, List<NaturalBuyerConversationScenario.Turn> scenario,
                                int seedThroughTurn) {
        return readInternal(transcript, scenario, seedThroughTurn, false).replies();
    }

    static SeedTranscript readTranscript(Path transcript, List<NaturalBuyerConversationScenario.Turn> scenario,
                                         int seedThroughTurn) {
        return readInternal(transcript, scenario, seedThroughTurn, true);
    }

    private static SeedTranscript readInternal(Path transcript, List<NaturalBuyerConversationScenario.Turn> scenario,
                                               int seedThroughTurn, boolean requireSnapshotHash) {
        Objects.requireNonNull(transcript, "transcript is required");
        Objects.requireNonNull(scenario, "scenario turns are required");
        if (seedThroughTurn < 1) throw new IllegalArgumentException("seed prefix must include at least one turn");

        final String markdown;
        try {
            markdown = Files.readString(transcript);
        } catch (IOException failure) {
            throw new IllegalArgumentException("seed transcript could not be read", failure);
        }
        if (!TERMINAL.matcher(markdown).find()) {
            throw new IllegalArgumentException("seed transcript has no validation terminal marker");
        }
        var snapshotHash = snapshotHash(markdown, requireSnapshotHash);

        var failedTurn = failedTurn(markdown);
        var headers = new ArrayList<MatcherMatch>();
        var matcher = TURN_HEADER.matcher(markdown);
        while (matcher.find()) headers.add(new MatcherMatch(Integer.parseInt(matcher.group(1)), matcher.start(), matcher.end()));

        if (seedThroughTurn > scenario.size()) {
            throw new IllegalArgumentException("missing seed turn " + seedThroughTurn + " in scenario");
        }
        var replies = new ArrayList<SeedReply>(seedThroughTurn);
        for (var number = 1; number <= seedThroughTurn; number++) {
            if (failedTurn != null && number >= failedTurn) {
                throw new IllegalArgumentException("failed turn " + failedTurn + " cannot be used as seed");
            }
            if (number > headers.size()) throw new IllegalArgumentException("missing seed turn " + number);
            var header = headers.get(number - 1);
            if (header.turnNumber() != number) throw new IllegalArgumentException("missing seed turn " + number);
            var sectionEnd = number < headers.size() ? headers.get(number).start() : terminalStart(markdown, header.end());
            var section = markdown.substring(header.end(), sectionEnd);
            var buyer = block(section, "**Buyer**", "**Assistant**");
            var assistant = blockUntilAny(section, "**Assistant**",
                    List.of("**Reply origin:**", "**Tools used**", "**Replay prefetch**",
                            "**Precision diagnostics**", "## "));
            var toolEvents = new ArrayList<LocalQwenTranscriptWriter.ToolTrace>();
            toolEvents.addAll(parseToolEvents(section, "**Tools used**",
                    List.of("**Reply origin:**", "**Replay prefetch**", "**Recorded seed tool evidence**",
                            "**Precision diagnostics**", "## "),
                    LocalQwenTranscriptWriter.ToolTraceOrigin.MODEL));
            toolEvents.addAll(parseToolEvents(section, "**Replay prefetch**",
                    List.of("**Recorded seed tool evidence**", "**Precision diagnostics**", "## "),
                    LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH));
            toolEvents.addAll(parseToolEvents(section, "**Recorded seed tool evidence**",
                    List.of("**Precision diagnostics**", "## "),
                    LocalQwenTranscriptWriter.ToolTraceOrigin.RECORDED_SEED));
            var expected = scenario.get(number - 1);
            if (expected.number() != number || !expected.message().equals(buyer)) {
                throw new IllegalArgumentException("buyer message mismatch at seed turn " + number);
            }
            if (assistant.isBlank()) throw new IllegalArgumentException("blank assistant reply at seed turn " + number);
            replies.add(new SeedReply(number, buyer, assistant, toolEvents));
        }
        return new SeedTranscript(snapshotHash, replies);
    }

    private static String snapshotHash(String markdown, boolean required) {
        var matcher = SNAPSHOT_HASH.matcher(markdown);
        if (!matcher.find()) {
            if (required) throw new IllegalArgumentException("seed transcript snapshot manifest hash is missing or invalid");
            return null;
        }
        var value = matcher.group(1);
        if (matcher.find() || markdown.lines().filter(line -> line.startsWith("Snapshot manifest SHA-256:")).count() != 1) {
            throw new IllegalArgumentException("seed transcript snapshot manifest hash must appear exactly once");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static Integer failedTurn(String markdown) {
        var failed = FAILED_TURN.matcher(markdown);
        if (failed.find()) return Integer.parseInt(failed.group(1));
        if (markdown.contains("## Replay stopped")) {
            var stopped = STOPPED_TURN.matcher(markdown);
            if (stopped.find()) return Integer.parseInt(stopped.group(1));
        }
        return null;
    }

    private static int terminalStart(String markdown, int from) {
        var terminal = TERMINAL.matcher(markdown);
        return terminal.find(from) ? terminal.start() : markdown.length();
    }

    private static String block(String section, String startMarker, String endMarker) {
        var start = section.indexOf(startMarker);
        if (start < 0) throw new IllegalArgumentException("seed transcript is missing " + startMarker);
        start += startMarker.length();
        var end = section.indexOf(endMarker, start);
        if (end < 0) throw new IllegalArgumentException("seed transcript is missing " + endMarker);
        return section.substring(start, end).strip();
    }

    private static String blockUntilAny(String section, String startMarker, List<String> endMarkers) {
        var start = section.indexOf(startMarker);
        if (start < 0) throw new IllegalArgumentException("seed transcript is missing " + startMarker);
        start += startMarker.length();
        var end = section.length();
        for (var marker : endMarkers) {
            var index = section.indexOf(marker, start);
            if (index >= 0 && index < end) end = index;
        }
        return section.substring(start, end).strip();
    }

    private static List<LocalQwenTranscriptWriter.ToolTrace> parseToolEvents(
            String section, String header, List<String> endMarkers,
            LocalQwenTranscriptWriter.ToolTraceOrigin origin) {
        var start = section.indexOf(header);
        if (start < 0) return List.of();
        start += header.length();
        var end = section.length();
        for (var marker : endMarkers) {
            var index = section.indexOf(marker, start);
            if (index >= 0 && index < end) end = index;
        }
        var block = section.substring(start, end).strip();
        if (block.isEmpty() || block.equals("No tool call recorded")) return List.of();
        var traces = new ArrayList<LocalQwenTranscriptWriter.ToolTrace>();
        for (var line : block.lines().map(String::strip).filter(line -> !line.isEmpty()).toList()) {
            var matcher = TOOL_TRACE.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("invalid tool trace row in seed transcript");
            }
            var toolName = matcher.group(1);
            if (!SAFE_TOOL_NAMES.contains(toolName)) {
                throw new IllegalArgumentException("invalid tool trace identifier in seed transcript");
            }
            Integer evidenceCount = matcher.group(3) == null ? null : parseEvidenceCount(matcher.group(3));
            traces.add(new LocalQwenTranscriptWriter.ToolTrace(toolName, matcher.group(2), evidenceCount, origin));
        }
        return List.copyOf(traces);
    }

    private static Integer parseEvidenceCount(String raw) {
        try {
            var value = Integer.parseInt(raw);
            if (value < 0) throw new NumberFormatException("negative evidence count");
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("invalid tool trace evidence count in seed transcript", failure);
        }
    }

    record SeedReply(int turnNumber, String buyerMessage, String assistantReply,
                     List<LocalQwenTranscriptWriter.ToolTrace> toolEvents) {
        SeedReply(int turnNumber, String buyerMessage, String assistantReply) {
            this(turnNumber, buyerMessage, assistantReply, List.of());
        }

        SeedReply {
            toolEvents = List.copyOf(toolEvents == null ? List.of() : toolEvents);
        }
    }

    record SeedTranscript(String snapshotSha256, List<SeedReply> replies) {
        SeedTranscript {
            replies = List.copyOf(replies == null ? List.of() : replies);
        }
    }

    private record MatcherMatch(int turnNumber, int start, int end) { }
}
