package com.ford.service.conversations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DeepSeekReplayTranscriptWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesClientRepliesAndToolTracesInOrder() throws Exception {
        var writer = new DeepSeekReplayTranscriptWriter(temporaryDirectory, "run-10", 2);
        var firstTurn = new NaturalBuyerConversationScenario.Turn(1, "Apenas busco camioneta.",
                NaturalBuyerConversationScenario.Expectation.SOCIAL);
        var secondTurn = new NaturalBuyerConversationScenario.Turn(2, "La usaría para ir al trabajo.",
                NaturalBuyerConversationScenario.Expectation.DISCOVERY);

        writer.append(firstTurn, "Con gusto. ¿Qué uso le dará?", List.of());
        writer.append(secondTurn, "Revisaré opciones con ese uso.",
                List.of(new DeepSeekReplayTranscriptWriter.ToolTrace("search_vehicles", "returned", 3)));
        writer.complete();

        var contents = Files.readString(writer.path());
        assertThat(writer.path()).startsWith(temporaryDirectory.resolve("deepseek"))
                .hasFileName("2-turn-transcript-run-10.md");
        assertThat(contents).contains("UNVERIFIED_RAW_DEEPSEEK", "2/2 turns complete",
                "SOCIAL", "Apenas busco camioneta.", "Con gusto. ¿Qué uso le dará?",
                "DISCOVERY", "La usaría para ir al trabajo.", "Revisaré opciones con ese uso.",
                "search_vehicles", "returned", "3 evidence items");
        assertThat(contents.indexOf("Turn 001")).isLessThan(contents.indexOf("Turn 002"));
        assertThat(contents).doesNotContain("DEEPSEEK_API_KEY", "Authorization:", "tool_arguments",
                "raw provider request", "raw provider response");
    }

    @Test
    void recordsFailedTurnWithoutInventingAssistantReply() throws Exception {
        var writer = new DeepSeekReplayTranscriptWriter(temporaryDirectory, "failed-run", 2);
        var firstTurn = new NaturalBuyerConversationScenario.Turn(1, "Hola.",
                NaturalBuyerConversationScenario.Expectation.SOCIAL);
        var failedTurn = new NaturalBuyerConversationScenario.Turn(2, "Prefiero cabina para cuatro.",
                NaturalBuyerConversationScenario.Expectation.DISCOVERY);

        writer.append(firstTurn, "Hola, con gusto le ayudo.", List.of());
        writer.recordFailure(failedTurn, DeepSeekReplayTranscriptWriter.FailureCategory.ASSISTANT_UNAVAILABLE_RETRY,
                List.of(new DeepSeekReplayTranscriptWriter.ToolTrace("search_vehicles", "failed", null)));

        var contents = Files.readString(writer.path());
        assertThat(contents).contains("Prefiero cabina para cuatro.", "ASSISTANT_UNAVAILABLE_RETRY",
                "search_vehicles", "failed", "Hola, con gusto le ayudo.")
                .doesNotContain("Assistant reply: [missing]", "2/2 turns complete");
        assertThat(contents.indexOf("Prefiero cabina para cuatro.")).isGreaterThan(
                contents.indexOf("Hola, con gusto le ayudo."));
        assertThat(contents.substring(contents.indexOf("## Replay stopped")))
                .contains("Client (no validated reply captured)")
                .doesNotContain("**MÍA**");
    }

    @Test
    void rejectsUnsafeRunIds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DeepSeekReplayTranscriptWriter(temporaryDirectory, "../escape", 2));
    }

    @Test
    void rejectsOutOfOrderTurnsBlankRepliesAndUnsafeToolTraces() throws Exception {
        var writer = new DeepSeekReplayTranscriptWriter(temporaryDirectory, "validation-run", 2);
        var secondTurn = new NaturalBuyerConversationScenario.Turn(2, "Segundo turno.",
                NaturalBuyerConversationScenario.Expectation.DISCOVERY);
        var firstTurn = new NaturalBuyerConversationScenario.Turn(1, "Primer turno.",
                NaturalBuyerConversationScenario.Expectation.SOCIAL);

        assertThatIllegalArgumentException().isThrownBy(() -> writer.append(secondTurn, "Respuesta.", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> writer.append(firstTurn, "  ", List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DeepSeekReplayTranscriptWriter.ToolTrace("bad tool", "returned", 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DeepSeekReplayTranscriptWriter.ToolTrace("search", "unknown", 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DeepSeekReplayTranscriptWriter.ToolTrace("search", "returned", -1));
    }
}
