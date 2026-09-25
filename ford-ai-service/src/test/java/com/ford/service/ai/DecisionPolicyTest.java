package com.ford.service.ai;

import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.DecisionPolicy;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionPolicyTest {

    private final DecisionPolicy policy = new DecisionPolicy();

    @Test
    void neverCreatesHandoffWhenTheFeatureIsDisabled() {
        assertThat(policy.mayCreateHandoff(decision(HandoffRecommendation.USER_REQUESTED), false))
                .isFalse();
        assertThat(policy.mayCreateHandoff(decision(HandoffRecommendation.NONE), true))
                .isFalse();
    }

    @Test
    void exposesOnlyAvailableToolsForTheClassifiedFamily() {
        var decision = decision(ToolFamily.FINANCE_ESTIMATE);

        assertThat(policy.allowedToolNames(decision,
                Set.of("finance_estimate", "vehicle_snapshot_search")))
                .containsExactly("finance_estimate");
        assertThat(policy.allowedToolNames(decision, Set.of("vehicle_snapshot_search")))
                .isEmpty();
    }

    @Test
    void restrictsProviderForConfirmedSensitiveData() {
        assertThat(policy.shouldUseRestrictedProvider(decision(SensitiveDataLevel.CONFIRMED)))
                .isTrue();
        assertThat(policy.shouldUseRestrictedProvider(decision(SensitiveDataLevel.POSSIBLE)))
                .isFalse();
    }

    private static ConversationDecision decision(HandoffRecommendation recommendation) {
        return new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, recommendation, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of());
    }

    private static ConversationDecision decision(ToolFamily family) {
        return new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, family, SensitiveDataLevel.NONE, List.of());
    }

    private static ConversationDecision decision(SensitiveDataLevel sensitiveData) {
        return new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE, sensitiveData, List.of());
    }
}
