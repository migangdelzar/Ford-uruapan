package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReplayCatalogFactFollowUpTest {
    @Test
    void recognizesCatalogFactQuestionEvenWhenIntentRouterDoesNot() {
        assertThat(LocalReplayCatalogFactFollowUp.matches("¿La ficha dice si es gasolina o híbrida?"))
                .isTrue();
        assertThat(LocalReplayCatalogFactFollowUp.matches("¿Y qué dato de seguridad aparece para esa misma versión?"))
                .isTrue();
        assertThat(LocalReplayCatalogFactFollowUp.matches("¿Me compartes de dónde sale ese dato del motor?"))
                .isTrue();
    }

    @Test
    void ignoresSocialAndUnrelatedMessages() {
        assertThat(LocalReplayCatalogFactFollowUp.matches("Gracias, justo eso quería explicar."))
                .isFalse();
        assertThat(LocalReplayCatalogFactFollowUp.matches("¿Me puedes conectar con un especialista?"))
                .isFalse();
    }
}
