package com.ford.service.eval;

import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationDecisionPort;
import com.ford.service.ai.application.ConversationDecisionService;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadNextAction;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.configuration.ClassificationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationRunnerTest {

    @Test
    void passesCourtesyCaseWithoutCallingAModel() {
        var evaluationCase = new IntentEvaluationCase("general-courtesy",
                IntentEvaluationCase.GeneralSubtype.COURTESY, "Gracias.", "",
                ConversationIntent.GENERAL, ToolFamily.NONE, IntentEvaluationCase.ToolMode.FORBIDDEN,
                List.of(ToolFamily.VEHICLE_SEARCH), IntentEvaluationCase.ExpectedBehavior.ACKNOWLEDGE,
                "courtesy regression");
        var report = new IntentEvaluationRunner().run(manifest(evaluationCase));

        assertThat(report.failedCases()).isZero();
        assertThat(report.passedCases()).isEqualTo(1);
    }

    @Test
    void reportsIntentToolAndRequiredToolFailures() {
        var service = fixedDecisionService(ConversationDecision.deterministicDefault());
        var evaluationCase = new IntentEvaluationCase("inventory-mismatch", null,
                "Busco una camioneta.", "", ConversationIntent.INVENTORY, ToolFamily.VEHICLE_SEARCH,
                IntentEvaluationCase.ToolMode.REQUIRED, List.of(),
                IntentEvaluationCase.ExpectedBehavior.ANSWER, "routing regression");
        var report = new IntentEvaluationRunner(service).run(manifest(evaluationCase));

        assertThat(report.failedCases()).isEqualTo(1);
        assertThat(report.cases()).singleElement().satisfies(result -> {
            assertThat(result.actualIntent()).isEqualTo(ConversationIntent.GENERAL);
            assertThat(result.actualToolFamily()).isEqualTo(ToolFamily.NONE);
            assertThat(result.failureCodes()).contains("INTENT_MISMATCH", "TOOL_FAMILY_MISMATCH",
                    "TOOL_REQUIRED_MISSING");
        });
    }

    @Test
    void reportsForbiddenToolFamilySelection() {
        var service = fixedDecisionService(new ConversationDecision(ConversationIntent.GENERAL,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_SEARCH, SensitiveDataLevel.NONE, LeadNextAction.NO_ACTION,
                false, "synthetic", List.of()));
        var evaluationCase = new IntentEvaluationCase("general-forbidden-tool",
                IntentEvaluationCase.GeneralSubtype.NO_TOOL_NEEDED, "Hola.", "",
                ConversationIntent.GENERAL, ToolFamily.NONE, IntentEvaluationCase.ToolMode.FORBIDDEN,
                List.of(ToolFamily.VEHICLE_SEARCH), IntentEvaluationCase.ExpectedBehavior.ANSWER,
                "tool guard");
        var report = new IntentEvaluationRunner(service).run(manifest(evaluationCase));

        assertThat(report.cases()).singleElement().satisfies(result ->
                assertThat(result.failureCodes()).contains("FORBIDDEN_TOOL_SELECTED",
                        "FORBIDDEN_TOOL_FAMILY_SELECTED"));
    }

    @Test
    void summarizesOnlyIntentsPresentInTheManifest() {
        var evaluationCase = new IntentEvaluationCase("general-courtesy",
                IntentEvaluationCase.GeneralSubtype.COURTESY, "Gracias.", "",
                ConversationIntent.GENERAL, ToolFamily.NONE, IntentEvaluationCase.ToolMode.FORBIDDEN,
                List.of(), IntentEvaluationCase.ExpectedBehavior.ACKNOWLEDGE, "courtesy");
        var report = new IntentEvaluationRunner().run(manifest(evaluationCase));

        assertThat(report.byIntent()).containsExactly(
                new IntentEvaluationReport.IntentSummary("GENERAL", 1, 1));
    }

    private static IntentEvaluationManifest manifest(IntentEvaluationCase evaluationCase) {
        return new IntentEvaluationManifest("runner-test", "v1", List.of(evaluationCase));
    }

    private static ConversationDecisionService fixedDecisionService(ConversationDecision decision) {
        ConversationDecisionPort fixed = context -> decision;
        var properties = new ClassificationProperties();
        properties.setEnabled(false);
        properties.setMode("disabled");
        return new ConversationDecisionService(fixed, fixed, properties);
    }
}
