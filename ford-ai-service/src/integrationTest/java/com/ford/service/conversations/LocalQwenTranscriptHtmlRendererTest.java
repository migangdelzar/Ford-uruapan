package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalQwenTranscriptHtmlRendererTest {

    @Test
    void rendersRecordedToolEventsAlongsideTheirTurnAndEscapesConversationText() {
        var turn = new LocalQwenTranscriptHtmlRenderer.TurnRecord(1, "DISCOVERY",
                "¿Busco Ranger <script>alert('x')</script>? &",
                "Reviso la ficha <b>ahora</b>.",
                List.of(new LocalQwenTranscriptWriter.ToolTrace("search_vehicles", "returned", 3)));

        var html = LocalQwenTranscriptHtmlRenderer.render("run-1", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT22_BASELINE, 10,
                LocalQwenTranscriptWriter.ReplayMode.RAW_QWEN, List.of(turn), null, false);

        assertThat(html).contains("UNVERIFIED_RAW_QWEN", "Turn 001 — DISCOVERY", "Tools used",
                "search_vehicles", "returned", "3 evidence items", "&lt;script&gt;", "&amp;",
                "Snapshot label: oct22-baseline", "Snapshot manifest SHA-256: manifest-hash")
                .doesNotContain("<script>", "<b>ahora</b>", "toolArguments", "tenantId");
    }

    @Test
    void distinguishesNoCallFromRecordedFailureAndNeverInventsFailedReply() {
        var committed = new LocalQwenTranscriptHtmlRenderer.TurnRecord(1, "SOCIAL",
                "Gracias.", "A la orden.", List.of());
        var failed = new LocalQwenTranscriptHtmlRenderer.FailedTurn(2,
                "¿Y las promociones vigentes?", "SAFE_FALLBACK_REVIEW",
                List.of(new LocalQwenTranscriptWriter.ToolTrace("list_promotions", "failed", null)), false);

        var html = LocalQwenTranscriptHtmlRenderer.render("run-2", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT22_BASELINE, 10,
                LocalQwenTranscriptWriter.ReplayMode.RAW_QWEN, List.of(committed), failed, false);

        assertThat(html).contains("No tool call recorded", "list_promotions", "failed",
                "Replay stopped", "¿Y las promociones vigentes?")
                .doesNotContain("Assistant (not committed)", "no se pudo consultar" /* fabricated assistant reply */);
    }

    @Test
    void labelsPrefetchedReplaySeparatelyAndKeepsPrefetchTraceDistinct() {
        var turn = new LocalQwenTranscriptHtmlRenderer.TurnRecord(1, "COMPARISON", "Compara dos trims",
                "Respuesta escrita por Qwen.", List.of(new LocalQwenTranscriptWriter.ToolTrace("compare_trims",
                "returned", 1, LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH)));

        var html = LocalQwenTranscriptHtmlRenderer.render("prefetch-run", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 1,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN, List.of(turn), null, false);

        assertThat(html).contains("PREFETCHED_QWEN", "Replay prefetch", "compare_trims",
                "Snapshot label: oct25-candidate");
    }

    private static LocalCatalogSnapshotLoader.SnapshotSummary snapshot() {
        return new LocalCatalogSnapshotLoader.SnapshotSummary(Instant.parse("2026-10-21T20:00:00Z"), Map.of(
                "vehicle-search.csv", 39, "vehicle-variants.csv", 121, "vehicle-facts.csv", 4881,
                "comparison-facts.csv", 2447, "promotions.csv", 10, "product-vectors.csv", 299), "manifest-hash");
    }
}
