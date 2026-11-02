package com.ford.service.handoffcontact.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlternateContactRedactorTest {
    private final AlternateContactRedactor redactor = new AlternateContactRedactor();

    @Test
    void removesKnownContactValuesWithoutChangingSurroundingText() {
        var result = redactor.redact("Prefiero Ana.Rios@example.com y teléfono +525512345678, gracias.",
                List.of("Ana.Rios@example.com", "+525512345678"));

        assertThat(result).isEqualTo("Prefiero [dato de contacto omitido] y teléfono [dato de contacto omitido], gracias.");
    }

    @Test
    void redactsLongerOverlappingValueFirst() {
        var result = redactor.redact("Llamar 55 1234 5678", List.of("55 1234", "55 1234 5678"));

        assertThat(result).isEqualTo("Llamar [dato de contacto omitido]");
    }

    @Test
    void leavesTextUntouchedWhenNoContactValuesAreKnown() {
        assertThat(redactor.redact("Busco una camioneta familiar", List.of()))
                .isEqualTo("Busco una camioneta familiar");
    }
}
