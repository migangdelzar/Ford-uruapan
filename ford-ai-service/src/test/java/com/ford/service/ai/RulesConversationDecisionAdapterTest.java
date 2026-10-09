package com.ford.service.ai;

import com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter;
import com.ford.service.ai.application.ClassificationContext;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.ToolFamily;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RulesConversationDecisionAdapterTest {

    private final RulesConversationDecisionAdapter adapter =
            new RulesConversationDecisionAdapter(new IntentRouter());

    @Test
    void classifiesInventoryAndExposesTheVehicleSearchFamily() {
        var decision = adapter.decide(context("¿Tienen Ranger disponible?"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.INVENTORY);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_SEARCH);
    }

    @Test
    void classifiesFinancingAndExposesTheFinanceFamily() {
        var decision = adapter.decide(context("Quiero una mensualidad"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.FINANCE);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.FINANCE_ESTIMATE);
    }

    @Test
    void classifiesHumanRequestWithoutCallingAnExternalProvider() {
        var decision = adapter.decide(context("Quiero hablar con un asesor"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.HUMAN_REQUEST);
        assertThat(decision.handoffRecommendation()).isEqualTo(HandoffRecommendation.USER_REQUESTED);
    }

    @Test
    void keepsGreetingAndBlankInputSafe() {
        assertThat(adapter.decide(context("Hola")).intent()).isEqualTo(ConversationIntent.GENERAL);
        assertThat(adapter.decide(context(" ")).intent()).isEqualTo(ConversationIntent.GENERAL);
    }

    @Test
    void mapsComparisonTypesToNarrowToolFamilies() {
        assertThat(adapter.decide(context("Compara Territory Trend vs Titanium 2027")).toolFamily())
                .isEqualTo(ToolFamily.TRIM_COMPARISON);
        assertThat(adapter.decide(context("Expedition 2026 vs 2027")).toolFamily())
                .isEqualTo(ToolFamily.YEAR_COMPARISON);
        assertThat(adapter.decide(context("Compara Territory con Bronco Sport")).toolFamily())
                .isEqualTo(ToolFamily.MODEL_COMPARISON);
        assertThat(adapter.decide(context("Expedition contra Chevrolet Tahoe")).toolFamily())
                .isEqualTo(ToolFamily.COMPETITOR_COMPARISON);
    }

    @Test
    void routesOfferIntentToPromotionToolFamily() {
        var decision = adapter.decide(context("Promociones vigentes para Bronco"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.OFFERS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.PROMOTIONS);
    }

    @Test
    void separatesCreditPlanLookupFromPaymentCalculation() {
        assertThat(adapter.decide(context("¿Qué tasa ofrece Ford Credit? ")).toolFamily())
                .isEqualTo(ToolFamily.FINANCE_PLANS);
        assertThat(adapter.decide(context("Calcula mi mensualidad estimada")).toolFamily())
                .isEqualTo(ToolFamily.FINANCE_ESTIMATE);
        assertThat(adapter.decide(context("Busco una SUV con financiamiento y mensualidad")).toolFamily())
                .isEqualTo(ToolFamily.VEHICLE_FINANCE);
    }

    private static ClassificationContext context(String message) {
        return new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), message,
                null, "BOT_ACTIVE", false);
    }
}
