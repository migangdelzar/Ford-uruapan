package com.ford.service.eval;

import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.ToolFamily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationReportWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesDeterministicJsonAndMarkdownWithoutMessageText() throws Exception {
        var report = new IntentEvaluationReport("intent-routing", "v1", List.of(
                new IntentEvaluationCaseResult("general-courtesy", ConversationIntent.GENERAL,
                        ConversationIntent.GENERAL, ToolFamily.NONE, ToolFamily.NONE,
                        IntentEvaluationCase.ToolMode.FORBIDDEN, true, List.of()),
                new IntentEvaluationCaseResult("inventory-family", ConversationIntent.INVENTORY,
                        ConversationIntent.GENERAL, ToolFamily.VEHICLE_SEARCH, ToolFamily.NONE,
                        IntentEvaluationCase.ToolMode.REQUIRED, false,
                        List.of("INTENT_MISMATCH", "TOOL_FAMILY_MISMATCH", "TOOL_REQUIRED_MISSING"))));
        var writer = new IntentEvaluationReportWriter(temporaryDirectory);

        var first = writer.write(report);
        var firstJson = Files.readString(first.json());
        var firstMarkdown = Files.readString(first.markdown());
        var second = writer.write(report);

        assertThat(first.json()).hasFileName("intent-routing-v1.json");
        assertThat(first.markdown()).hasFileName("intent-routing-v1.md");
        assertThat(firstJson).contains("\"suiteId\" : \"intent-routing\"", "\"totalCases\" : 2",
                "\"passedCases\" : 1", "\"failedCases\" : 1", "\"GENERAL\"", "\"INVENTORY\"",
                "INTENT_MISMATCH");
        assertThat(firstMarkdown).contains("# Intent Evaluation Report", "- Suite: `intent-routing`",
                "- Version: `v1`", "| GENERAL | 1 | 1 |", "| INVENTORY | 0 | 1 |", "general-courtesy");
        assertThat(Files.readString(second.json())).isEqualTo(firstJson);
        assertThat(Files.readString(second.markdown())).isEqualTo(firstMarkdown);
        assertThat(firstJson + firstMarkdown).doesNotContain("DEEPSEEK_API_KEY", "Authorization:",
                "memoryContext", "provider payload");
    }
}
