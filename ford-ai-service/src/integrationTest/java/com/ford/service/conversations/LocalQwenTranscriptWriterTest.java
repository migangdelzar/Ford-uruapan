package com.ford.service.conversations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class LocalQwenTranscriptWriterTest {
    @TempDir Path tempDir;

    @Test
    void writesToolTraceToMarkdownAndSiblingHtmlFromTheSameTurn() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-tools", snapshot(), 1);
        var turn = new NaturalBuyerConversationScenario.Turn(1, "¿Qué pickup recomienda?",
                NaturalBuyerConversationScenario.Expectation.DISCOVERY);

        writer.append(turn, "Voy a consultar el catálogo.",
                List.of(new LocalQwenTranscriptWriter.ToolTrace("search_vehicles", "returned", 3)));
        writer.complete();

        assertThat(Files.readString(writer.path())).contains("search_vehicles", "returned", "3 evidence items");
        assertThat(writer.htmlPath()).exists().hasFileName("1-turn-transcript-run-tools.html");
        assertThat(Files.readString(writer.htmlPath())).contains("search_vehicles", "returned", "3 evidence items");
    }

    @Test
    void labelsRecordedSeedRepliesSeparatelyFromLiveQwenReplies() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-seeded", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 2,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN);
        var first = new NaturalBuyerConversationScenario.Turn(1, "Hola.",
                NaturalBuyerConversationScenario.Expectation.SOCIAL);
        var second = new NaturalBuyerConversationScenario.Turn(2, "¿Qué pickup recomienda?",
                NaturalBuyerConversationScenario.Expectation.DISCOVERY);

        writer.append(first, "Hola, ¿en qué puedo ayudarle?", List.of(new LocalQwenTranscriptWriter.ToolTrace(
                        "get_vehicle", "returned", 2, LocalQwenTranscriptWriter.ToolTraceOrigin.RECORDED_SEED)),
                LocalQwenTranscriptWriter.ReplyOrigin.RECORDED_SEED);
        writer.append(second, "¿Qué uso le dará?", List.of(), LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN);
        writer.complete(1);

        var markdown = Files.readString(writer.path());
        var html = Files.readString(writer.htmlPath());
        assertThat(markdown).contains("Reply origin:** RECORDED_SEED", "Reply origin:** LIVE_QWEN",
                "RECORDED_SEED replies: 1", "not the final all-live acceptance",
                "**Recorded seed tool evidence**", "`get_vehicle` — returned — 2 evidence items");
        assertThat(html).contains("RECORDED_SEED", "LIVE_QWEN", "RECORDED_SEED replies",
                "Recorded seed tool evidence", "get_vehicle", "Execution mode: LIVE_DIAGNOSTIC");
    }

    @Test
    void refusesPrecisionEvidenceForRecordedSeedReplies() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "seed-no-precision", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 1,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN);
        var turn = new NaturalBuyerConversationScenario.Turn(1, "¿Qué pickup me recomendaste?",
                NaturalBuyerConversationScenario.Expectation.CATALOG_FACT);
        writer.append(turn, "La respuesta grabada dice Ranger.", List.of(),
                LocalQwenTranscriptWriter.ReplyOrigin.RECORDED_SEED);

        assertThatIllegalStateException().isThrownBy(() -> writer.recordPrecision(turn,
                new LocalReplayPrecisionEvaluator.PrecisionResult(List.of("Ranger"), List.of(), List.of(), List.of()),
                null)).withMessageContaining("RECORDED_SEED");
    }

    @Test
    void rejectsLiveCatalogTracesAttachedToARecordedSeedReply() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "seed-live-trace", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 1,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN);
        var turn = new NaturalBuyerConversationScenario.Turn(1, "Compara versiones",
                NaturalBuyerConversationScenario.Expectation.COMPARISON);

        assertThatIllegalArgumentException().isThrownBy(() -> writer.append(turn, "Respuesta grabada.", List.of(
                new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH)),
                LocalQwenTranscriptWriter.ReplyOrigin.RECORDED_SEED))
                .withMessageContaining("seed replies cannot carry live tool traces");
    }

    @Test
    void freshAcceptanceRequiresOneHundredLiveTurnsAndRejectsSeededOrigins() throws Exception {
        var shortRun = new LocalQwenTranscriptWriter(tempDir, "fresh-short", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 1,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN,
                LocalQwenReplayExecutionMode.FRESH_FULL_LIVE);
        shortRun.append(new NaturalBuyerConversationScenario.Turn(1, "Hola.",
                NaturalBuyerConversationScenario.Expectation.SOCIAL), "Hola.");
        assertThatIllegalStateException().isThrownBy(shortRun::completeFreshFullLive)
                .withMessageContaining("exactly 100");

        var seeded = new LocalQwenTranscriptWriter(tempDir, "fresh-seeded", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 100,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN,
                LocalQwenReplayExecutionMode.FRESH_FULL_LIVE);
        for (var number = 1; number <= 100; number++) {
            var turn = new NaturalBuyerConversationScenario.Turn(number, "Turno " + number,
                    NaturalBuyerConversationScenario.Expectation.SOCIAL);
            seeded.append(turn, "Hola.", List.of(), number == 1
                    ? LocalQwenTranscriptWriter.ReplyOrigin.RECORDED_SEED
                    : LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN);
        }
        assertThatIllegalStateException().isThrownBy(seeded::completeFreshFullLive)
                .withMessageContaining("all replies must be live Qwen");
    }

    @Test
    void supportsOriginalTurnNumbersForCheckpointWindowArtifacts() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "window-86", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 2,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN,
                LocalQwenReplayExecutionMode.CHECKPOINT_LIVE_WINDOW, 86);
        writer.recordCheckpointMetadata(new LocalQwenTranscriptWriter.CheckpointMetadata(
                "natural-buyer-turn-085", "f".repeat(64), 85));
        writer.append(new NaturalBuyerConversationScenario.Turn(86, "Pregunta turno 86",
                NaturalBuyerConversationScenario.Expectation.CATALOG_FACT), "Respuesta live 86");
        writer.append(new NaturalBuyerConversationScenario.Turn(87, "Pregunta turno 87",
                NaturalBuyerConversationScenario.Expectation.CATALOG_FACT), "Respuesta live 87");
        writer.recordHandoffCount(0);
        writer.complete();

        assertThat(Files.readString(writer.path())).contains("Turn 086", "Turn 087",
                "Execution mode: CHECKPOINT_LIVE_WINDOW", "Checkpoint fixture ID: natural-buyer-turn-085",
                "Scenario SHA-256: " + "f".repeat(64), "Checkpoint turn: 85", "Handoff count: 0");
        assertThat(Files.readString(writer.htmlPath())).contains("natural-buyer-turn-085",
                "Scenario SHA-256", "Checkpoint turn: 85");
    }

    @Test
    void marksFreshAcceptanceOnlyAfterOneHundredLiveRepliesAndVerifiedZeroHandoffs() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "fresh-acceptance", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 100,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN,
                LocalQwenReplayExecutionMode.FRESH_FULL_LIVE);
        for (var number = 1; number <= 100; number++) {
            writer.append(new NaturalBuyerConversationScenario.Turn(number, "Buyer turn " + number,
                            NaturalBuyerConversationScenario.Expectation.SOCIAL), "Live response " + number);
        }
        writer.recordHandoffCount(0);
        writer.completeFreshFullLive();

        assertThat(Files.readString(writer.path())).contains("100/100 live Qwen turns; seeded replies: 0; handoffs: 0",
                "Execution mode: FRESH_FULL_LIVE", "Handoff count: 0");
        assertThat(Files.readString(writer.htmlPath())).contains("Fresh all-live acceptance passed");
    }

    @Test
    void rendersPrefetchInSeparateSectionFromModelSelectedTools() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-prefetch", snapshot(), 1);
        writer.append(new NaturalBuyerConversationScenario.Turn(1, "Compara dos versiones",
                        NaturalBuyerConversationScenario.Expectation.COMPARISON),
                "Comparación redactada por Qwen.", List.of(
                        new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                                LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH)));

        var markdown = Files.readString(writer.path());
        var html = Files.readString(writer.htmlPath());
        assertThat(markdown).contains("**Tools used**", "No tool call recorded", "**Replay prefetch**",
                "`compare_trims`");
        assertThat(html).contains("<h3>Tools used</h3>", "<h3>Replay prefetch</h3>", "compare_trims");
        assertThat(markdown.indexOf("**Tools used**")).isLessThan(markdown.indexOf("**Replay prefetch**"));
    }

    @Test
    void writesPrefetchedModeToDistinctArtifactWhileLegacyConstructorsStayRaw() throws Exception {
        var raw = new LocalQwenTranscriptWriter(tempDir, "same-run", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 1);
        var prefetched = new LocalQwenTranscriptWriter(tempDir, "same-run", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 1,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN);

        assertThat(raw.path().getFileName()).hasToString("1-turn-transcript-same-run.md");
        assertThat(prefetched.path()).isNotEqualTo(raw.path());
        assertThat(prefetched.path().getFileName()).hasToString("1-turn-prefetched-qwen-transcript-same-run.md");
        assertThat(Files.readString(raw.path())).contains("Status: UNVERIFIED_RAW_QWEN");
        assertThat(Files.readString(prefetched.path())).contains("Status: PREFETCHED_QWEN",
                "local replay prefetch", "Snapshot label: oct25-candidate",
                "Snapshot manifest SHA-256: manifest-hash");
        assertThat(Files.readString(prefetched.htmlPath())).contains("PREFETCHED_QWEN",
                "Snapshot manifest SHA-256: manifest-hash");
    }

    @Test
    void reportsSeparatePerTurnAndAggregatePrecisionWithDenominatorsAndCoverage() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-precision", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 1,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN);
        var turn = new NaturalBuyerConversationScenario.Turn(1, "Recuerda mi uso",
                NaturalBuyerConversationScenario.Expectation.MEMORY_RECALL,
                List.of("weekday_work"));
        writer.append(turn, "Dijiste trabajo entre semana.");
        writer.recordPrecision(turn,
                new LocalReplayPrecisionEvaluator.PrecisionResult(List.of("Ranger 2027"),
                        List.of("$999,000 MXN"), List.of("claim unknown"), List.of()),
                new LocalReplayPrecisionEvaluator.PrecisionResult(List.of("weekday_work"),
                        List.of(), List.of(), List.of()));
        writer.complete();

        var report = Files.readString(writer.path());
        assertThat(report).contains("Catalog claim precision", "supported=1", "unsupported=1", "unscored=1",
                "precision=50%", "coverage=67%", "Memory recall precision", "recall misses=0",
                "1/1 turns complete");
        assertThat(report).doesNotContain("100/100 turns complete");
    }

    @Test
    void reportsNAWhenPrecisionHasNoScoredClaims() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-precision-na", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 1,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN);
        var turn = new NaturalBuyerConversationScenario.Turn(1, "Pregunta de catálogo",
                NaturalBuyerConversationScenario.Expectation.CATALOG_FACT);
        writer.append(turn, "Una afirmación no reconocida.");
        writer.recordPrecision(turn,
                new LocalReplayPrecisionEvaluator.PrecisionResult(List.of(), List.of(), List.of("unknown"), List.of()),
                null);
        writer.complete();

        assertThat(Files.readString(writer.path())).contains("Catalog claim precision: supported=0 / denominator=0",
                "precision=N/A", "coverage=0%");
    }

    @Test
    void recordsSelectedSnapshotLabelAndManifestHashInBothTranscriptFormats() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-candidate", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 1);

        var markdown = Files.readString(writer.path());
        var html = Files.readString(writer.htmlPath());

        assertThat(markdown).contains("Snapshot label: oct25-candidate", "Snapshot manifest SHA-256: manifest-hash");
        assertThat(html).contains("Snapshot label: oct25-candidate", "Snapshot manifest SHA-256: manifest-hash");
    }

    @Test
    void writesActualExchangeThenSafeFailureMarkerWithoutClaimingCompletion() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-123", snapshot());

        writer.append(new NaturalBuyerConversationScenario.Turn(1, "Hola, busco una camioneta.",
                        NaturalBuyerConversationScenario.Expectation.SOCIAL),
                "Hola, con gusto le ayudo.");
        writer.recordFailure(new NaturalBuyerConversationScenario.Turn(2,
                        "¿Puede revisar la búsqueda local?", NaturalBuyerConversationScenario.Expectation.DISCOVERY),
                LocalQwenTranscriptWriter.FailureCategory.SAFE_FALLBACK_REVIEW);

        var report = Files.readString(writer.path());
        assertThat(writer.path()).startsWith(tempDir.resolve("local-qwen"));
        assertThat(writer.path().getFileName().toString()).isEqualTo("100-turn-transcript-run-123.md");
        assertThat(report).contains("UNVERIFIED_RAW_QWEN", "Hola, busco una camioneta.",
                "Hola, con gusto le ayudo.", "Failed turn: 2", "¿Puede revisar la búsqueda local?",
                "SAFE_FALLBACK_REVIEW");
        assertThat(report).doesNotContain("Authorization:", "toolArguments", "100/100 turns complete");
    }

    @Test
    void recordsQualityFailureAfterCommittedReplyWithoutMislabelingItAsUncommitted() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-committed-failure", snapshot(), 10);
        var turn = new NaturalBuyerConversationScenario.Turn(1, "¿Qué pickup me conviene?",
                NaturalBuyerConversationScenario.Expectation.DISCOVERY);

        writer.append(turn, "Revisaré opciones con la información disponible.");
        writer.recordFailure(turn, LocalQwenTranscriptWriter.FailureCategory.INVALID_EVIDENCE);

        var report = Files.readString(writer.path());
        var html = Files.readString(writer.htmlPath());
        assertThat(report).contains("Revisaré opciones con la información disponible.",
                "Reply committed; validation failed", "Failed validation at turn: 1", "INVALID_EVIDENCE");
        assertThat(report).doesNotContain("Buyer (reply not committed)");
        assertThat(html).contains("Revisaré opciones con la información disponible.",
                "Reply committed; validation failed", "INVALID_EVIDENCE");
        assertThat(report).doesNotContain("10/10 turns complete");
    }

    @Test
    void recordsMissingComparisonEvidenceAndSafePrefetchStatusAfterCommittedReply() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-comparison-failure", snapshot(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, 10,
                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN);
        var turn = new NaturalBuyerConversationScenario.Turn(1,
                "¿La comparación incluye alguna característica de seguridad para cada una?",
                NaturalBuyerConversationScenario.Expectation.COMPARISON);
        writer.append(turn, "No tengo información confirmada para comparar seguridad.");

        writer.recordFailure(turn, LocalQwenTranscriptWriter.FailureCategory.MISSING_CATALOG_EVIDENCE,
                List.of(new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 0)),
                LocalReplayPrecisionEvidence.ComparisonPrefetchStatus.PAIR_UNRESOLVED);

        var report = Files.readString(writer.path());
        assertThat(report).contains("MISSING_CATALOG_EVIDENCE",
                "Comparison prefetch status: PAIR_UNRESOLVED", "Reply committed; validation failed");
        assertThat(report).doesNotContain("REPLAY PREFETCHED COMPARISON EVIDENCE", "buyer-history payload",
                "10/10 turns complete");
    }

    @Test
    void rejectsCompletionUntilExactlyOneHundredRepliesWereWritten() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-456", snapshot());

        assertThatIllegalStateException().isThrownBy(writer::complete);
    }

    @Test
    void marksCompleteOnlyAfterOneHundredSequentialPairs() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-789", snapshot());
        for (var number = 1; number <= 100; number++) {
            writer.append(new NaturalBuyerConversationScenario.Turn(number, "Turno sintético " + number,
                            NaturalBuyerConversationScenario.Expectation.DISCOVERY),
                    "Respuesta sintética " + number);
        }

        writer.complete();

        assertThat(Files.readString(writer.path())).contains("UNVERIFIED_RAW_QWEN", "100/100 turns complete");
    }

    @Test
    void marksTenTurnCheckpointCompleteOnlyAfterTenSequentialPairs() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-10", snapshot(), 10);
        for (var number = 1; number <= 10; number++) {
            writer.append(new NaturalBuyerConversationScenario.Turn(number, "Turno natural " + number,
                            NaturalBuyerConversationScenario.Expectation.DISCOVERY),
                    "Respuesta comprometida " + number);
        }

        writer.complete();

        assertThat(writer.path().getFileName().toString()).isEqualTo("10-turn-transcript-run-10.md");
        assertThat(Files.readString(writer.path())).contains("UNVERIFIED_RAW_QWEN", "10/10 turns complete");
    }

    @Test
    void recordsSafeSnapshotProvenanceWithoutExportPathOrRows() throws Exception {
        var writer = new LocalQwenTranscriptWriter(tempDir, "run-source", snapshot());

        var report = Files.readString(writer.path());

        assertThat(report).contains(LocalCatalogSnapshotLoader.SOURCE_LABEL,
                "Snapshot refreshed at (UTC): 2026-10-21T20:00:00Z",
                "vehicle variants: 121", "promotions: 10", "product vectors: 299");
        assertThat(report).doesNotContain(tempDir.toString(), "Authorization:", "connectionString", "192.168.");
    }

    @Test
    void rejectsUnsafeRunIdentifierBeforeCreatingAPath() throws Exception {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalQwenTranscriptWriter(tempDir, "../outside", snapshot()));
    }

    private static LocalCatalogSnapshotLoader.SnapshotSummary snapshot() {
        return new LocalCatalogSnapshotLoader.SnapshotSummary(Instant.parse("2026-10-21T20:00:00Z"), Map.of(
                "vehicle-search.csv", 39, "vehicle-variants.csv", 121, "vehicle-facts.csv", 4881,
                "comparison-facts.csv", 2447, "promotions.csv", 10, "product-vectors.csv", 299), "manifest-hash");
    }
}
