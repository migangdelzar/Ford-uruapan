package com.ford.service.ai;

import com.ford.service.ai.application.ClassificationContext;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationDecisionService;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.ConversationDecisionPort;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.configuration.ClassificationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationDecisionServiceTest {

    @Test
    void recordsRulesFallbackWhenThePrimaryClassifierFails() {
        ConversationDecisionPort primary = context -> { throw new IllegalStateException("provider unavailable"); };
        ConversationDecision fallback = new ConversationDecision(ConversationIntent.GENERAL,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE,
                SensitiveDataLevel.NONE, List.of());
        ConversationDecisionPort rules = context -> fallback;
        var properties = new ClassificationProperties();
        var service = new ConversationDecisionService(primary, rules, properties);

        var decision = service.decide(context("Hola"));

        assertThat(decision).isSameAs(fallback);
    }

    @Test
    void usesRulesWhenClassificationIsDisabled() {
        var properties = new ClassificationProperties();
        properties.setEnabled(false);
        var primaryCalled = new boolean[1];
        ConversationDecisionPort primary = context -> {
            primaryCalled[0] = true;
            throw new AssertionError("primary must not be called");
        };
        ConversationDecisionPort rules = context -> new ConversationDecision(
                ConversationIntent.GENERAL, LeadTemperature.UNKNOWN, LeadStage.NEW,
                HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of());

        new ConversationDecisionService(primary, rules, properties).decide(context("Hola"));

        assertThat(primaryCalled[0]).isFalse();
    }

    private static ClassificationContext context(String message) {
        return new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), message,
                null, "BOT_ACTIVE", false);
    }
}
