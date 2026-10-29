package com.ford.service.channels.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InboundDebounceSignalsTest {

    @Test
    void ordinaryCatalogQuestionDoesNotBypassTheDebounceWindow() {
        assertThat(InboundDebounceService.bypassesDebounce("¿Precio de Ranger?")).isFalse();
        assertThat(InboundDebounceService.bypassesDebounce("Cuánto cuesta una Bronco")).isFalse();
    }

    @Test
    void directHumanRequestBypassesTheDebounceWindow() {
        assertThat(InboundDebounceService.bypassesDebounce("Quiero hablar con un asesor")).isTrue();
        assertThat(InboundDebounceService.bypassesDebounce("Me pueden conectar con una persona")).isTrue();
    }

    @Test
    void optOutBypassesTheDebounceWindow() {
        assertThat(InboundDebounceService.bypassesDebounce("No me escriban más")).isTrue();
        assertThat(InboundDebounceService.bypassesDebounce("Dejen de enviarme mensajes")).isTrue();
    }

    @Test
    void urgentComplaintBypassesTheDebounceWindow() {
        assertThat(InboundDebounceService.bypassesDebounce("Queja urgente por un cobro")).isTrue();
        assertThat(InboundDebounceService.bypassesDebounce("Necesito una respuesta inmediata")).isTrue();
    }

    @Test
    void blankOrMissingTextDoesNotBypass() {
        assertThat(InboundDebounceService.bypassesDebounce(null)).isFalse();
        assertThat(InboundDebounceService.bypassesDebounce("   ")).isFalse();
    }
}
