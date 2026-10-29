package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalReplayYearlessAmbiguityAssertionsTest {
    private static final String GASOLINA = "2027 XLT Gasolina 4x2";
    private static final String DIESEL = "2027 XLT Diésel 4x4";
    private static final String DIESEL_URL =
            "https://www.forduruapan.mx/RANGER/27/Comparativo/XLT-DIESEL-4X4/";

    @Test
    void acceptsYearAndTrimClarificationWithoutVariantSpecificLinks() {
        var answer = "Para revisar la versión exacta de Ranger, ¿cuál busca: " + GASOLINA + " o " + DIESEL + "?";

        assertThatCode(() -> LocalReplayYearlessAmbiguityAssertions.assertYearlessTrimClarification(answer))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsVariantSpecificDieselLinkOnYearlessClarification() {
        var answer = "Para revisar la versión exacta de Ranger, ¿cuál busca: " + GASOLINA + " o " + DIESEL
                + "? " + DIESEL_URL;

        assertThatThrownBy(() -> LocalReplayYearlessAmbiguityAssertions.assertYearlessTrimClarification(answer))
                .isInstanceOf(AssertionError.class);
    }
}
