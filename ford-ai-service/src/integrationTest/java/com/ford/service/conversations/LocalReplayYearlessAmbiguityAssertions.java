package com.ford.service.conversations;

import static org.assertj.core.api.Assertions.assertThat;

final class LocalReplayYearlessAmbiguityAssertions {
    private LocalReplayYearlessAmbiguityAssertions() { }

    static void assertYearlessTrimClarification(String answer) {
        assertThat(answer).as("turn 31 must clarify both year/version identities")
                .contains("¿cuál busca:", "2027 XLT Gasolina 4x2", "2027 XLT Diésel 4x4")
                .doesNotContain("/Comparativo/");
    }
}
