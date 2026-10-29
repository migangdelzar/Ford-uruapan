package com.ford.service.ai;

import com.ford.service.ai.application.ClassificationEvidence;
import com.ford.service.ai.application.ClassifierType;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.LeadNextAction;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationDecisionTest {

    @Test
    void rejectsMissingDecisionValues() {
        assertThatThrownBy(() -> new ConversationDecision(
                null, LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.NONE, SensitiveDataLevel.NONE, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void copiesEvidenceAndRendersOnlyStableDecisionFields() {
        var evidence = new ArrayList<>(List.of(new ClassificationEvidence(
                ClassifierType.INTENT, "FINANCE", 0.91, "model_choice", "typesafe", "jev", 12, false)));
        var decision = new ConversationDecision(
                ConversationIntent.FINANCE, LeadTemperature.WARM, LeadStage.QUALIFYING,
                HandoffRecommendation.NONE, ToolFamily.FINANCE_ESTIMATE,
                SensitiveDataLevel.NONE, evidence);

        evidence.clear();

        assertThat(decision.evidence()).hasSize(1);
        assertThat(decision.toPromptContext()).isEqualTo(
                "[DECISION CONTEXT]\n" +
                        "intent=finance\n" +
                        "lead_stage=qualifying\n" +
                        "lead_temperature=warm\n" +
                        "tool_family=finance_estimate\n" +
                        "handoff=none\n" +
                        "sensitive_data=none\n" +
                        "next_action=no_action\n" +
                        "urgency_signal=false\n" +
                        "vehicle_model=none\n" +
                        "vehicle_model_source=none\n" +
                        "vehicle_model_year=none\n" +
                        "vehicle_model_year_source=none\n" +
                        "vehicle_comparison_years=\n" +
                        "vehicle_trim=none\n" +
                        "vehicle_trim_source=none\n" +
                        "vehicle_resolution_status=missing\n" +
                        "summary=\n" +
                        "[/DECISION CONTEXT]");
        assertThat(decision.toPromptContext()).doesNotContain("typesafe", "0.91", "model_choice");
    }

    @Test
    void keepsOperationalLeadSignalsTypedAndBounded() {
        var decision = new ConversationDecision(
                ConversationIntent.OFFERS, LeadTemperature.WARM, LeadStage.QUALIFYING,
                HandoffRecommendation.NONE, ToolFamily.OFFERS, SensitiveDataLevel.NONE,
                LeadNextAction.FOLLOW_UP, true, "  Quiere conocer promociones vigentes.  ", List.of());

        assertThat(decision.nextAction()).isEqualTo(LeadNextAction.FOLLOW_UP);
        assertThat(decision.urgencySignal()).isTrue();
        assertThat(decision.summary()).isEqualTo("Quiere conocer promociones vigentes.");
        assertThat(decision.summary()).hasSizeLessThanOrEqualTo(280);
    }

    @Test
    void mapsNewIntentValuesToTheLegacyIntentVocabulary() {
        assertThat(decision(ConversationIntent.VEHICLE_DETAILS).toLegacyIntent())
                .isEqualTo(com.ford.service.ai.application.Intent.CATALOG);
        assertThat(decision(ConversationIntent.HUMAN_REQUEST).toLegacyIntent())
                .isEqualTo(com.ford.service.ai.application.Intent.LEAD_HANDOFF);
    }

    private static ConversationDecision decision(ConversationIntent intent) {
        return new ConversationDecision(intent, LeadTemperature.UNKNOWN, LeadStage.NEW,
                HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of());
    }
}
