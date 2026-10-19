package com.ford.service.ai;

import com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter;
import com.ford.service.ai.application.ClassificationContext;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.DecisionPolicy;
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
    void classifiesNonToolLeadRequestsWithoutInventingToolFamilies() {
        assertDecision("Quiero dar mi auto a cuenta", ConversationIntent.TRADE_IN, ToolFamily.NONE);
        assertDecision("¿Cuál es el horario de la agencia?", ConversationIntent.DEALERSHIP_INFO, ToolFamily.NONE);
        assertDecision("Necesito servicio para mi F-150", ConversationIntent.SERVICE_SUPPORT,
                ToolFamily.KNOWLEDGE_SEARCH);
        assertDecision("Quiero presentar una queja por la atención", ConversationIntent.COMPLAINT_STATUS,
                ToolFamily.NONE);
        assertDecision("¿Me recomienda una receta médica?", ConversationIntent.OUT_OF_SCOPE, ToolFamily.NONE);
    }

    @Test
    void classifiesAppointmentSeparatelyFromVehicleDetails() {
        assertDecision("Quiero agendar una prueba de manejo", ConversationIntent.APPOINTMENT,
                ToolFamily.APPOINTMENT);
    }

    @Test
    void treatsPurchaseInterestAsLeadIntentNotARequestForAHuman() {
        var decision = adapter.decide(context("Quiero comprar una Bronco"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.handoffRecommendation()).isEqualTo(HandoffRecommendation.NONE);
    }

    @Test
    void routesModelYearComparisonsToTheCatalogCallbackFamily() {
        var decision = adapter.decide(context("¿Qué cambió entre BRONCO modelo 2025 y 2026?"));
        var allowed = new DecisionPolicy().allowedToolNames(decision,
                java.util.Set.of("compare_model_years", "search_offers"));

        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
        assertThat(allowed).containsExactly("compare_model_years");
    }

    private void assertDecision(String message, ConversationIntent intent, ToolFamily family) {
        var decision = adapter.decide(context(message));
        assertThat(decision.intent()).as(message).isEqualTo(intent);
        assertThat(decision.toolFamily()).as(message).isEqualTo(family);
    }

    private static ClassificationContext context(String message) {
        return new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), message,
                null, "BOT_ACTIVE", false);
    }
}
