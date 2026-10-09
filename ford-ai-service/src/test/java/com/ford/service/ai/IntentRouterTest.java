package com.ford.service.ai;

import com.ford.service.ai.application.Intent;
import com.ford.service.ai.application.IntentRouter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterTest {

    private final IntentRouter router = new IntentRouter();

    @Test
    void routesSpanishInventoryQuestionWithoutCallingModel() {
        assertThat(router.route("¿Tienen Ranger azul disponible en Uruapan?"))
                .isEqualTo(Intent.INVENTORY);
    }

    @Test
    void routesFinancingQuestion() {
        assertThat(router.route("¿Cuánto quedaría la mensualidad y qué comisión cobran?"))
                .isEqualTo(Intent.FINANCE);
    }

    @Test
    void routesAmbiguousQuestionToGeneralAssistant() {
        assertThat(router.route("Hola, tengo una duda"))
                .isEqualTo(Intent.GENERAL);
    }

    @Test
    void routesFordLoboToVehicleCatalog() {
        assertThat(router.route("Me interesa una Ford Lobo"))
                .isEqualTo(Intent.CATALOG);
    }

    @Test
    void routesRaptorInterestToVehicleCatalog() {
        assertThat(router.route("Estoy interesado en la Raptor"))
                .isEqualTo(Intent.CATALOG);
    }

    @Test
    void routesTrimYearAndCompetitorComparisonsToVehicleDetails() {
        assertThat(router.route("Compara Expedition 2027 Active vs Platinum"))
                .isEqualTo(Intent.CATALOG);
        assertThat(router.route("¿Qué cambia entre Expedition 2025 y 2027?"))
                .isEqualTo(Intent.CATALOG);
        assertThat(router.route("Expedition contra Chevrolet Tahoe"))
                .isEqualTo(Intent.CATALOG);
    }

    @Test
    void routesFuelEconomyQuestionsToVehicleKnowledge() {
        assertThat(router.route("¿Cuántos kilómetros por litro da?"))
                .isEqualTo(Intent.KNOWLEDGE);
    }

    @Test
    void routesExplicitHumanPurchaseRequestToLeadHandoff() {
        assertThat(router.route("Quiero comprarla este mes, que me llame un asesor"))
                .isEqualTo(Intent.LEAD_HANDOFF);
    }
}
