package com.ford.service.ai;

import com.ford.service.ai.application.ClassificationContext;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationDecisionService;
import com.ford.service.ai.application.DecisionPolicy;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.ConversationDecisionPort;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.configuration.ClassificationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationDecisionServiceTest {

    @Test
    void usesDeterministicRulesInsteadOfSendingSensitiveTextToPrimaryClassifier() {
        var primaryCalled = new boolean[1];
        ConversationDecisionPort primary = context -> {
            primaryCalled[0] = true;
            throw new AssertionError("Sensitive text must not reach the primary classifier");
        };
        ConversationDecisionPort rules = context -> new ConversationDecision(
                ConversationIntent.GENERAL, LeadTemperature.UNKNOWN, LeadStage.NEW,
                HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.CONFIRMED, List.of());
        var context = new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), "masked input", null,
                "BOT_ACTIVE", true);

        var decision = new ConversationDecisionService(primary, rules, new ClassificationProperties()).decide(context);

        assertThat(primaryCalled[0]).isFalse();
        assertThat(decision.sensitiveDataLevel()).isEqualTo(SensitiveDataLevel.CONFIRMED);
    }

    @Test
    void restoresVolatileToolRouteWhenPrimaryClassifierMissesAnOffersQuestion() {
        var primaryDecision = new ConversationDecision(ConversationIntent.GENERAL,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE, ToolFamily.NONE,
                SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var service = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties());

        var decision = service.decide(context("Quiero conocer qué modelos tienen promoción"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.OFFERS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.OFFERS);
        assertThat(decision.leadTemperature()).isEqualTo(LeadTemperature.WARM);
        assertThat(decision.leadStage()).isEqualTo(LeadStage.QUALIFYING);
        assertThat(new DecisionPolicy().allowedToolNames(decision, Set.of("list_promotions", "search_offers")))
                .contains("list_promotions", "search_offers");
    }

    @Test
    void replacesAConflictingSpecializedToolRouteWithTheDeterministicRoute() {
        var primaryDecision = new ConversationDecision(ConversationIntent.FINANCE,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.KNOWLEDGE_SEARCH, SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var service = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties());

        var decision = service.decide(context("Quiero una mensualidad"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.FINANCE);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.FINANCE_ESTIMATE);
    }

    @Test
    void preventsPrimaryToolCallsForDeterministicallyGeneralOrOutOfScopeMessages() {
        var primaryDecision = new ConversationDecision(ConversationIntent.OUT_OF_SCOPE,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.KNOWLEDGE_SEARCH, SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var service = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties());

        var decision = service.decide(context("Escribe un poema"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.OUT_OF_SCOPE);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.NONE);
    }

    @Test
    void keepsTheMoreSpecificPrimaryVehicleRouteWhenRulesOnlyHaveAKnowledgeRoute() {
        var primaryDecision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var service = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties());

        var decision = service.decide(context("¿Qué rendimiento de gasolina tiene la Ford Escape? "));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
    }

    @Test
    void keepsJeVVehicleToolForShortTrimFollowUpWhenMemoryEstablishesFordContext() {
        var primaryDecision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var classificationContext = new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), "La XLT",
                "Historial reciente: ¿Qué promociones tienen para Ranger?", "BOT_ACTIVE", false);

        var decision = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties()).decide(classificationContext);

        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
    }

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

    @Test
    void usesRulesWhenATypeSafeClassifierReturnsLowConfidenceEvidence() {
        var properties = new ClassificationProperties();
        properties.setMinConfidence(0.8);
        var lowConfidence = new ConversationDecision(ConversationIntent.FINANCE,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.FINANCE_ESTIMATE, SensitiveDataLevel.NONE,
                List.of(new com.ford.service.ai.application.ClassificationEvidence(
                        com.ford.service.ai.application.ClassifierType.INTENT, "FINANCE", 0.4,
                        "model_choice", "typesafe", "test", 1, false)));
        ConversationDecisionPort rules = context -> new ConversationDecision(
                ConversationIntent.GENERAL, LeadTemperature.UNKNOWN, LeadStage.NEW,
                HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of());

        var decision = new ConversationDecisionService(context -> lowConfidence, rules, properties)
                .decide(context("mensualidad"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.GENERAL);
    }

    @Test
    void routesTerritoryToVehicleDetailsWhenThePrimaryClassifierFallsBackOnLowConfidence() {
        var properties = new ClassificationProperties();
        properties.setMinConfidence(0.8);
        var lowConfidence = new ConversationDecision(ConversationIntent.GENERAL,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.NONE, SensitiveDataLevel.NONE,
                List.of(new com.ford.service.ai.application.ClassificationEvidence(
                        com.ford.service.ai.application.ClassifierType.INTENT, "GENERAL", 0.4,
                        "model_choice", "typesafe", "test", 1, false)));
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var decision = new ConversationDecisionService(context -> lowConfidence, rules, properties)
                .decide(context("Territory"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
        assertThat(new DecisionPolicy().allowedToolNames(decision, Set.of("get_vehicle", "search_vehicles")))
                .contains("get_vehicle", "search_vehicles");
    }

    private static ClassificationContext context(String message) {
        return new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), message,
                null, "BOT_ACTIVE", false);
    }
}
