package com.ford.service.conversations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LocalQwenTranscriptSeedReaderTest {
    @TempDir Path tempDir;

    @Test
    void readsOnlyValidatedSequentialBuyerAndAssistantTurns() throws Exception {
        var transcript = tempDir.resolve("prior-run.md");
        Files.writeString(transcript, """
                # Local Qwen transcript

                ## Turn 001 — SOCIAL

                **Buyer**

                Hola.

                **Assistant**

                Hola, ¿en qué puedo ayudarle?

                **Tools used**

                No tool call recorded

                ## Turn 002 — DISCOVERY

                **Buyer**

                Busco una pickup.

                **Assistant**

                ¿Qué presupuesto tiene?

                **Precision diagnostics**

                ## Complete — PREFETCHED_QWEN

                2/2 turns complete
                """);
        var turns = List.of(
                new NaturalBuyerConversationScenario.Turn(1, "Hola.", NaturalBuyerConversationScenario.Expectation.SOCIAL),
                new NaturalBuyerConversationScenario.Turn(2, "Busco una pickup.", NaturalBuyerConversationScenario.Expectation.DISCOVERY));

        var replies = LocalQwenTranscriptSeedReader.read(transcript, turns, 2);

        assertThat(replies).containsExactly(
                new LocalQwenTranscriptSeedReader.SeedReply(1, "Hola.", "Hola, ¿en qué puedo ayudarle?"),
                new LocalQwenTranscriptSeedReader.SeedReply(2, "Busco una pickup.", "¿Qué presupuesto tiene?"));
    }

    @Test
    void rejectsAReplyCommittedButMarkedAsFailed() throws Exception {
        var transcript = tempDir.resolve("failed-run.md");
        Files.writeString(transcript, """
                ## Turn 001 — COMPARISON

                **Buyer**

                Compara Ranger 2027 XL y XLT.

                **Assistant**

                Respuesta que luego falló el gate.

                **Precision diagnostics**

                ## Validation failed after committed reply — turn 1

                Safe reason: MISSING_CATALOG_EVIDENCE
                """);
        var turns = List.of(new NaturalBuyerConversationScenario.Turn(1, "Compara Ranger 2027 XL y XLT.",
                NaturalBuyerConversationScenario.Expectation.COMPARISON));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LocalQwenTranscriptSeedReader.read(transcript, turns, 1))
                .withMessageContaining("failed turn");
    }

    @Test
    void rejectsBuyerMismatchAndMissingPrefixTurn() throws Exception {
        var transcript = tempDir.resolve("mismatch-run.md");
        Files.writeString(transcript, """
                ## Turn 001 — SOCIAL

                **Buyer**

                Different buyer text.

                **Assistant**

                Reply.

                ## Complete — PREFETCHED_QWEN

                1/1 turns complete
                """);
        var expected = List.of(new NaturalBuyerConversationScenario.Turn(1, "Expected buyer text.",
                NaturalBuyerConversationScenario.Expectation.SOCIAL));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LocalQwenTranscriptSeedReader.read(transcript, expected, 1))
                .withMessageContaining("buyer message mismatch");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LocalQwenTranscriptSeedReader.read(transcript, expected, 2))
                .withMessageContaining("missing seed turn");
    }

    @Test
    void readsOnlyAllowlistedToolTraceScalarsFromCommittedTurns() throws Exception {
        var transcript = tempDir.resolve("traced-run.md");
        Files.writeString(transcript, """
                ## Turn 001 — COMPARISON

                **Buyer**

                Compara Ranger 2027 XL y XLT.

                **Assistant**

                Comparación respaldada.

                **Reply origin:** LIVE_QWEN

                **Tools used**

                - `get_vehicle` — started
                - `get_vehicle` — returned — 2 evidence items

                **Replay prefetch**

                - `compare_trims` — returned — 1 evidence item

                **Recorded seed tool evidence**

                - `get_vehicle` — returned — 3 evidence items

                ## Complete — PREFETCHED_QWEN
                """);
        var scenario = List.of(new NaturalBuyerConversationScenario.Turn(1,
                "Compara Ranger 2027 XL y XLT.", NaturalBuyerConversationScenario.Expectation.COMPARISON));

        var seeded = LocalQwenTranscriptSeedReader.read(transcript, scenario, 1).getFirst();

        assertThat(seeded.toolEvents()).containsExactly(
                new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "started", null,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.MODEL),
                new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 2,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.MODEL),
                new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH),
                new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 3,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.RECORDED_SEED));
    }

    @Test
    void readsAmbiguousToolTraceRowsFromValidatedTurns() throws Exception {
        var transcript = tempDir.resolve("ambiguous-trace.md");
        Files.writeString(transcript, """
                ## Turn 001 — CLARIFICATION

                **Buyer**

                Precio Ranger XLT.

                **Assistant**

                Hay dos variantes XLT.

                **Tools used**

                - `get_vehicle` — ambiguous — 0 evidence items

                ## Complete — PREFETCHED_QWEN
                """);
        var scenario = List.of(new NaturalBuyerConversationScenario.Turn(1, "Precio Ranger XLT.",
                NaturalBuyerConversationScenario.Expectation.CATALOG_FACT));

        var seeded = LocalQwenTranscriptSeedReader.read(transcript, scenario, 1).getFirst();

        assertThat(seeded.toolEvents()).containsExactly(new LocalQwenTranscriptWriter.ToolTrace(
                "get_vehicle", "ambiguous", 0, LocalQwenTranscriptWriter.ToolTraceOrigin.MODEL));
    }

    @Test
    void rejectsMalformedOrUnknownSuccessfulToolTraceRows() throws Exception {
        var transcript = tempDir.resolve("malformed-traces.md");
        Files.writeString(transcript, """
                ## Turn 001 — CATALOG_FACT

                **Buyer**

                Precio Ranger.

                **Assistant**

                Precio publicado.

                **Tools used**

                - `search_vehicles` — returned — -1 evidence items
                - `unknown_tool` — returned — 1 evidence item

                ## Complete — PREFETCHED_QWEN
                """);
        var scenario = List.of(new NaturalBuyerConversationScenario.Turn(1, "Precio Ranger.",
                NaturalBuyerConversationScenario.Expectation.CATALOG_FACT));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LocalQwenTranscriptSeedReader.read(transcript, scenario, 1))
                .withMessageContaining("tool trace");
    }

    @Test
    void returnsSnapshotIdentityAlongsideTheValidatedPrefix() throws Exception {
        var hash = "a".repeat(64);
        var transcript = tempDir.resolve("snapshot-run.md");
        Files.writeString(transcript, """
                Snapshot manifest SHA-256: %s

                ## Turn 001 — SOCIAL

                **Buyer**

                Hola.

                **Assistant**

                Hola.

                ## Complete — PREFETCHED_QWEN
                """.formatted(hash));
        var scenario = List.of(new NaturalBuyerConversationScenario.Turn(1, "Hola.",
                NaturalBuyerConversationScenario.Expectation.SOCIAL));

        var seed = LocalQwenTranscriptSeedReader.readTranscript(transcript, scenario, 1);

        assertThat(seed.snapshotSha256()).isEqualTo(hash);
        assertThat(seed.replies()).hasSize(1);
    }

    @Test
    void rejectsMissingOrMalformedSnapshotIdentity() throws Exception {
        var transcript = tempDir.resolve("missing-snapshot-run.md");
        Files.writeString(transcript, """
                ## Turn 001 — SOCIAL

                **Buyer**

                Hola.

                **Assistant**

                Hola.

                ## Complete — PREFETCHED_QWEN
                """);
        var scenario = List.of(new NaturalBuyerConversationScenario.Turn(1, "Hola.",
                NaturalBuyerConversationScenario.Expectation.SOCIAL));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LocalQwenTranscriptSeedReader.readTranscript(transcript, scenario, 1))
                .withMessageContaining("snapshot manifest hash");
        Files.writeString(transcript, "Snapshot manifest SHA-256: not-a-hash\n## Complete — PREFETCHED_QWEN\n");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LocalQwenTranscriptSeedReader.readTranscript(transcript, scenario, 1))
                .withMessageContaining("snapshot manifest hash");
    }
}
