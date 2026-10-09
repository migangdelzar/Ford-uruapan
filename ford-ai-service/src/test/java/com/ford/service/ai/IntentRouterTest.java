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
    void separatesPaymentCalculationFromFordCreditPlanLookup() {
        assertThat(router.route("Calcula una mensualidad estimada"))
                .isEqualTo(Intent.FINANCE);
        assertThat(router.route("¿Qué tasa ofrece Ford Credit?"))
                .isEqualTo(Intent.FINANCE_PLANS);
    }

    @Test
    void usesOneCombinedToolForVehicleOptionsAndFinancing() {
        assertThat(router.route("Busco una SUV con financiamiento y mensualidad"))
                .isEqualTo(Intent.VEHICLE_FINANCE);
    }

    @Test
    void routesAmbiguousQuestionToGeneralAssistant() {
        assertThat(router.route("Hola, tengo una duda"))
                .isEqualTo(Intent.GENERAL);
    }

    @Test
    void routesExplicitHumanPurchaseRequestToLeadHandoff() {
        assertThat(router.route("Quiero comprarla este mes, que me llame un asesor"))
                .isEqualTo(Intent.LEAD_HANDOFF);
    }

    @Test
    void routesTrimAndCompetitorComparisonsSeparatelyFromCatalogSearch() {
        assertThat(router.route("Compara Territory Trend vs Titanium 2027"))
                .isEqualTo(Intent.TRIM_COMPARISON);
        assertThat(router.route("Expedition King Ranch contra Chevrolet Tahoe"))
                .isEqualTo(Intent.COMPETITOR_COMPARISON);
    }

    @Test
    void routesYearAndModelComparisons() {
        assertThat(router.route("¿Qué cambió entre Expedition 2026 y 2027?"))
                .isEqualTo(Intent.YEAR_COMPARISON);
        assertThat(router.route("Compara Territory con Bronco Sport"))
                .isEqualTo(Intent.MODEL_COMPARISON);
    }

    @Test
    void routesPromotionsBeforeGeneralVehicleLookup() {
        assertThat(router.route("¿Qué promociones vigentes hay para Bronco?"))
                .isEqualTo(Intent.OFFERS);
    }
}
