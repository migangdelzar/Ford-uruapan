package com.ford.service.conversations;

import java.util.List;
import java.util.Objects;

/** Renders the same committed transcript data as the Markdown writer, with safe tool trace cards. */
final class LocalQwenTranscriptHtmlRenderer {

    private LocalQwenTranscriptHtmlRenderer() { }

    static String render(String runId, LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                         LocalCatalogSnapshotSelection.Label snapshotLabel, int expectedTurns,
                         LocalQwenTranscriptWriter.ReplayMode replayMode,
                         List<TurnRecord> turns, FailedTurn failedTurn, boolean completed) {
        return render(runId, snapshot, snapshotLabel, expectedTurns, replayMode, turns, failedTurn, completed, 0);
    }

    static String render(String runId, LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                         LocalCatalogSnapshotSelection.Label snapshotLabel, int expectedTurns,
                         LocalQwenTranscriptWriter.ReplayMode replayMode,
                         List<TurnRecord> turns, FailedTurn failedTurn, boolean completed, int seededPrefixTurns) {
        return render(runId, snapshot, snapshotLabel, expectedTurns, replayMode, turns, failedTurn, completed,
                seededPrefixTurns, LocalQwenReplayExecutionMode.LIVE_DIAGNOSTIC, null);
    }

    static String render(String runId, LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                         LocalCatalogSnapshotSelection.Label snapshotLabel, int expectedTurns,
                         LocalQwenTranscriptWriter.ReplayMode replayMode,
                         List<TurnRecord> turns, FailedTurn failedTurn, boolean completed, int seededPrefixTurns,
                         LocalQwenReplayExecutionMode executionMode) {
        return render(runId, snapshot, snapshotLabel, expectedTurns, replayMode, turns, failedTurn, completed,
                seededPrefixTurns, executionMode, null);
    }

    static String render(String runId, LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                         LocalCatalogSnapshotSelection.Label snapshotLabel, int expectedTurns,
                         LocalQwenTranscriptWriter.ReplayMode replayMode,
                         List<TurnRecord> turns, FailedTurn failedTurn, boolean completed, int seededPrefixTurns,
                         LocalQwenReplayExecutionMode executionMode,
                         LocalQwenTranscriptWriter.CheckpointMetadata checkpointMetadata) {
        return render(runId, snapshot, snapshotLabel, expectedTurns, replayMode, turns, failedTurn, completed,
                seededPrefixTurns, executionMode, checkpointMetadata, null);
    }

    static String render(String runId, LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                         LocalCatalogSnapshotSelection.Label snapshotLabel, int expectedTurns,
                         LocalQwenTranscriptWriter.ReplayMode replayMode,
                         List<TurnRecord> turns, FailedTurn failedTurn, boolean completed, int seededPrefixTurns,
                         LocalQwenReplayExecutionMode executionMode,
                         LocalQwenTranscriptWriter.CheckpointMetadata checkpointMetadata, Integer handoffCount) {
        return render(runId, snapshot, snapshotLabel, expectedTurns, replayMode, turns, failedTurn, completed,
                seededPrefixTurns, executionMode, checkpointMetadata, handoffCount, null);
    }

    static String render(String runId, LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                         LocalCatalogSnapshotSelection.Label snapshotLabel, int expectedTurns,
                         LocalQwenTranscriptWriter.ReplayMode replayMode,
                         List<TurnRecord> turns, FailedTurn failedTurn, boolean completed, int seededPrefixTurns,
                         LocalQwenReplayExecutionMode executionMode,
                         LocalQwenTranscriptWriter.CheckpointMetadata checkpointMetadata, Integer handoffCount,
                         String scenarioSha256) {
        Objects.requireNonNull(runId, "run ID is required");
        Objects.requireNonNull(snapshot, "snapshot metadata is required");
        Objects.requireNonNull(snapshotLabel, "snapshot label is required");
        Objects.requireNonNull(replayMode, "replay mode is required");
        Objects.requireNonNull(executionMode, "execution mode is required");
        var safeTurns = List.copyOf(turns == null ? List.of() : turns);
        var status = completed ? executionMode == LocalQwenReplayExecutionMode.FRESH_FULL_LIVE
                ? "Fresh all-live acceptance passed" : seededPrefixTurns == 0 ? "Complete" : "Seeded (not final)"
                : failedTurn == null ? "Running" : "Stopped";
        var modeLabel = replayMode.status();
        var html = new StringBuilder(8192);
        html.append("<!doctype html>\n<html lang=\"es-MX\"><head><meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(escape(modeLabel)).append(" — Local replay</title><style>")
                .append(" :root{color-scheme:light;--ink:#17211d;--muted:#61716a;--line:#dce5df;--paper:#f3f6f3;--card:#fff;--green:#126b4b;--blue:#355d8a;--amber:#8a4c08;}"
                        + "*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.55 system-ui,-apple-system,\"Segoe UI\",sans-serif}"
                        + "header{position:sticky;top:0;z-index:2;padding:16px max(20px,calc((100vw - 960px)/2));background:#10261f;color:#fff;box-shadow:0 2px 12px #10261f22}"
                        + "header strong{display:block;font-size:18px}header span{color:#c9ded3;font-size:13px}main{max-width:960px;margin:28px auto 72px;padding:0 20px}"
                        + "h1{font-size:clamp(26px,4vw,38px);line-height:1.2;margin:0 0 18px}h2{font-size:19px;margin:0}"
                        + ".meta,.turn,.stop{background:var(--card);border:1px solid var(--line);border-radius:14px;margin:14px 0;overflow:hidden;box-shadow:0 2px 8px #16261d08}"
                        + ".meta{padding:14px 18px;color:var(--muted);font-size:14px}.turn-head,.stop-head{padding:13px 18px;border-bottom:1px solid var(--line);display:flex;justify-content:space-between;gap:12px;align-items:center}"
                        + ".turn-body{padding:10px 18px 16px}.speaker{margin:12px 0 2px;font-size:12px;text-transform:uppercase;letter-spacing:.07em}.buyer{color:var(--green)}.assistant{color:var(--blue)}.text{white-space:pre-wrap;overflow-wrap:anywhere;margin:5px 0 12px}"
                        + ".tools{margin:14px 0 2px;padding:12px 14px;border:1px solid #d5e3f2;border-radius:10px;background:#f5f9fd}.tools h3{font-size:14px;margin:0 0 8px;color:#294e74}.tool{display:grid;grid-template-columns:minmax(160px,1fr) auto auto;gap:8px;align-items:center;padding:7px 0;border-top:1px solid #e1eaf3;font-size:14px}.tool-name{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;overflow-wrap:anywhere}.badge{border-radius:999px;background:#e7efe9;padding:2px 9px;color:#20543c;font-size:12px}.count{color:var(--muted);font-size:12px}.origin{color:var(--muted);font-size:12px;margin:0 0 8px}.empty{color:var(--muted);font-style:italic;font-size:14px}.stop{border-color:#e6ba82}.stop-head{background:#fff4e4;color:var(--amber)}"
                        + "@media(max-width:600px){.tool{grid-template-columns:1fr auto}.count{grid-column:1/-1}.turn-head{align-items:flex-start}}@media print{header{position:static}.turn,.stop{break-inside:avoid;box-shadow:none}body{background:#fff}}")
                .append("</style></head><body><header><strong>").append(escape(modeLabel)).append(" · ")
                .append(escape(status)).append(" · ").append(safeTurns.size()).append("/").append(expectedTurns)
                .append(" turns</strong><span>Local synthetic replay · exploratory only · factuality and safety not verified</span></header>")
                .append("<main><h1>").append(escape(modeLabel)).append(" — Local synthetic Qwen conversation</h1>")
                .append("<section class=\"meta\"><div>Run ID: ").append(escape(runId)).append("</div>")
                .append("<div>Source: ").append(escape(LocalCatalogSnapshotLoader.SOURCE_LABEL)).append("</div>")
                .append("<div>Snapshot label: ").append(escape(snapshotLabel.wireName())).append("</div>")
                .append("<div>Execution mode: ").append(escape(executionMode.name())).append("</div>")
                .append(scenarioSha256 == null ? "" : "<div>Scenario SHA-256: "
                        + escape(scenarioSha256) + "</div>")
                .append(checkpointMetadata == null ? "" : "<div>Checkpoint fixture ID: "
                        + escape(checkpointMetadata.checkpointId()) + "</div><div>Scenario SHA-256: "
                        + escape(checkpointMetadata.scenarioSha256()) + "</div><div>Checkpoint turn: "
                        + checkpointMetadata.checkpointTurn() + "</div>")
                .append(handoffCount == null ? "" : "<div>Handoff count: " + handoffCount + "</div>")
                .append("<div>Snapshot manifest SHA-256: ").append(escape(snapshot.sha256())).append("</div>")
                .append("<div>Snapshot refreshed (UTC): ").append(escape(snapshot.refreshedAt().toString())).append("</div>")
                .append("<div>Rows: variants ").append(rowCount(snapshot, "vehicle-variants.csv"))
                .append(" · promotions ").append(rowCount(snapshot, "promotions.csv"))
                .append(" · product vectors ").append(rowCount(snapshot, "product-vectors.csv"))
                .append(" · Status: ").append(escape(modeLabel)).append("</div></section>");

        for (var turn : safeTurns) {
            html.append("<article class=\"turn\"><div class=\"turn-head\"><h2>Turn ")
                    .append(String.format(java.util.Locale.ROOT, "%03d", turn.number()))
                    .append(" — ").append(escape(turn.expectation())).append("</h2></div><div class=\"turn-body\">")
                    .append("<h3 class=\"speaker buyer\">Buyer</h3><div class=\"text\">")
                    .append(escape(turn.buyer())).append("</div><h3 class=\"speaker assistant\">Assistant</h3><div class=\"text\">")
                    .append(escape(turn.assistant())).append("</div><p class=\"origin\">Reply origin: ")
                    .append(escape(turn.replyOrigin())).append("</p>");
            appendTools(html, turn.toolEvents());
            html.append("</div></article>");
        }
        if (failedTurn != null) {
            html.append("<article class=\"stop\"><div class=\"stop-head\"><h2>Replay stopped — turn ")
                    .append(String.format(java.util.Locale.ROOT, "%03d", failedTurn.number()))
                    .append("</h2></div><div class=\"turn-body\">");
            if (failedTurn.replyCommitted()) {
                html.append("<div>Reply committed; validation failed.</div>");
            } else {
                html.append("<h3 class=\"speaker buyer\">Buyer (reply not committed)</h3><div class=\"text\">")
                        .append(escape(failedTurn.buyer())).append("</div>");
            }
            html.append("<div>Safe reason: ")
                    .append(escape(failedTurn.safeReason())).append("</div>");
            appendTools(html, failedTurn.toolEvents());
            html.append("</div></article>");
        } else if (completed) {
            if (seededPrefixTurns == 0) {
                html.append("<p>Complete — ").append(escape(modeLabel)).append(" · ").append(expectedTurns).append("/")
                        .append(expectedTurns).append(" turns complete; all replies are live Qwen</p>");
            } else {
                html.append("<p>Seeded replay complete — not final acceptance. RECORDED_SEED replies: ")
                        .append(seededPrefixTurns).append("; LIVE_QWEN turns: ")
                        .append(expectedTurns - seededPrefixTurns).append(".</p>");
            }
        }
        return html.append("</main></body></html>\n").toString();
    }

    private static void appendTools(StringBuilder html, List<LocalQwenTranscriptWriter.ToolTrace> toolEvents) {
        var safeEvents = toolEvents == null ? List.<LocalQwenTranscriptWriter.ToolTrace>of() : toolEvents;
        appendToolGroup(html, "Tools used", safeEvents.stream()
                .filter(event -> event.origin() == LocalQwenTranscriptWriter.ToolTraceOrigin.MODEL).toList());
        var prefetch = safeEvents.stream()
                .filter(event -> event.origin() == LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH).toList();
        if (!prefetch.isEmpty()) appendToolGroup(html, "Replay prefetch", prefetch);
        var seeded = safeEvents.stream()
                .filter(event -> event.origin() == LocalQwenTranscriptWriter.ToolTraceOrigin.RECORDED_SEED).toList();
        if (!seeded.isEmpty()) appendToolGroup(html, "Recorded seed tool evidence", seeded);
    }

    private static void appendToolGroup(StringBuilder html, String label,
                                        List<LocalQwenTranscriptWriter.ToolTrace> toolEvents) {
        html.append("<section class=\"tools\"><h3>").append(escape(label)).append("</h3>");
        if (toolEvents.isEmpty()) {
            html.append("<div class=\"empty\">No tool call recorded</div>");
        } else {
            for (var event : toolEvents) {
                html.append("<div class=\"tool\"><span class=\"tool-name\">")
                        .append(escape(event.toolName())).append("</span><span class=\"badge\">")
                        .append(escape(event.outcome())).append("</span><span class=\"count\">");
                if (event.evidenceCount() != null) {
                    html.append(event.evidenceCount()).append(event.evidenceCount() == 1
                            ? " evidence item" : " evidence items");
                }
                html.append("</span></div>");
            }
        }
        html.append("</section>");
    }

    private static int rowCount(LocalCatalogSnapshotLoader.SnapshotSummary snapshot, String name) {
        return snapshot.rowCounts().getOrDefault(name, 0);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    record TurnRecord(int number, String expectation, String buyer, String assistant, String replyOrigin,
                      List<LocalQwenTranscriptWriter.ToolTrace> toolEvents) {
        TurnRecord(int number, String expectation, String buyer, String assistant,
                   List<LocalQwenTranscriptWriter.ToolTrace> toolEvents) {
            this(number, expectation, buyer, assistant, LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN.name(),
                    toolEvents);
        }

        TurnRecord {
            if (number < 1) throw new IllegalArgumentException("turn number must be positive");
            expectation = Objects.requireNonNull(expectation);
            buyer = Objects.requireNonNull(buyer);
            assistant = Objects.requireNonNull(assistant);
            replyOrigin = Objects.requireNonNull(replyOrigin);
            if (!List.of("LIVE_QWEN", "RECORDED_SEED").contains(replyOrigin)) {
                throw new IllegalArgumentException("reply origin is invalid");
            }
            toolEvents = List.copyOf(toolEvents == null ? List.of() : toolEvents);
        }
    }

    record FailedTurn(int number, String buyer, String safeReason,
                      List<LocalQwenTranscriptWriter.ToolTrace> toolEvents, boolean replyCommitted) {
        FailedTurn {
            if (number < 1) throw new IllegalArgumentException("turn number must be positive");
            buyer = Objects.requireNonNull(buyer);
            safeReason = Objects.requireNonNull(safeReason);
            toolEvents = List.copyOf(toolEvents == null ? List.of() : toolEvents);
        }
    }
}
