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

    @Test
    void routesMainVehicleOptionsToSearchAndTrimQuestionsToCatalogDetails() {
        assertDecision("¿Cuáles son las principales opciones de camionetas Ford?",
                ConversationIntent.INVENTORY, ToolFamily.VEHICLE_SEARCH);
        assertDecision("¿Qué modelos tienen?", ConversationIntent.INVENTORY, ToolFamily.VEHICLE_SEARCH);
        assertDecision("¿Me puedes orientar? ¿Qué opciones hay para una camioneta?",
                ConversationIntent.INVENTORY, ToolFamily.VEHICLE_SEARCH);
        assertDecision("¿Qué versiones tiene la Ranger?",
                ConversationIntent.VEHICLE_DETAILS, ToolFamily.VEHICLE_DETAILS);
    }

    @Test
    void treatsEarlyBuyerGuidanceAsDiscoveryNotCatalogLookup() {
        assertDecision("Hola, buenas tardes. ¿Me puedes orientar? Apenas estoy empezando a buscar camioneta.",
                ConversationIntent.GENERAL, ToolFamily.NONE);
    }

    @Test
    void routesNeedsBasedFordRecommendationToVehicleSearch() {
        assertDecision("¿Qué Ford me convendría revisar primero con ese uso mixto?",
                ConversationIntent.INVENTORY, ToolFamily.VEHICLE_SEARCH);
    }

    @Test
    void keepsPickupCategoryPreferenceInDiscoveryWithoutSearchingInventory() {
        assertDecision("Me interesa una pickup, aunque no quiero que sea demasiado difícil de estacionar en la ciudad.",
                ConversationIntent.GENERAL, ToolFamily.NONE);
    }

    @Test
    void treatsAdviceAboutWhichDimensionsToReviewAsDiscoveryNotCatalogLookup() {
        var decision = adapter.decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "Para mi familia, ¿qué dimensiones deberíamos revisar antes de decidir?",
                "Usuario: La caja de la Ranger XLT me atrae más, pero la XL quizá sea más práctica en ciudad.",
                "BOT_ACTIVE", false));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.GENERAL);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.NONE);
    }

    @Test
    void routesAnAnaphoricFordQuestionWhenMemoryNamesOneModel() {
        var decision = adapter.decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "¿Y esa?", "Historial reciente: cliente preguntó por Ford Ranger.", "BOT_ACTIVE", false));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
    }

    @Test
    void routesAnaphoricSpecificationFollowUpToVehicleDetailsWhenMemoryNamesOneModel() {
        var decision = adapter.decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "¿Y de esa versión, qué motor tiene?",
                "Usuario: Me interesa la Ranger 2027 XLT Gasolina 4x2. ¿Cuál es su precio publicado?\n"
                        + "Asistente: No pude confirmar un precio exacto para esa combinación.",
                "BOT_ACTIVE", false));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
    }

    @Test
    void doesNotGuessAnAnaphoricVehicleWhenMemoryNamesMultipleModels() {
        var decision = adapter.decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "¿Y esa?", "Historial reciente: comparó Ford Ranger y Ford Bronco.", "BOT_ACTIVE", false));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.GENERAL);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.NONE);
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
