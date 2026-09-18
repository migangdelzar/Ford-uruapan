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
}
