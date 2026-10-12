package com.ford.service.ai;

import com.ford.service.ai.application.Intent;
import com.ford.service.ai.application.IntentRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "Bronco", "Bronco Raptor", "Bronco Sport", "Edge Híbrida", "E Transit",
            "Expedition", "Explorer", "Explorer ST", "Explorer Police Interceptor",
            "F-150", "F-150 Híbrida", "F-250", "Lobo Híbrida", "Lobo Raptor", "Lobo",
            "Maverick Híbrida", "Mustang", "Mustang Mach E", "Mustang RTR",
            "Police Interceptor Utility", "Police Interceptor Utility Híbrida",
            "Ranger", "Ranger Raptor", "Super Duty Chasis", "Territory",
            "Territory Híbrida", "Transit Chasis", "Transit Custom", "Transit Pasajeros", "Transit Van"
    })
    void routesEverySupportedFordNameplateToVehicleCatalog(String model) {
        assertThat(router.route(model)).as(model).isEqualTo(Intent.CATALOG);
    }

    @Test
    void routesRaptorInterestToVehicleCatalog() {
        assertThat(router.route("Estoy interesado en la Raptor"))
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
