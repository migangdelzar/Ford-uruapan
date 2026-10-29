package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReplayPrecisionEvaluatorTest {
    private final LocalReplayPrecisionEvaluator evaluator = new LocalReplayPrecisionEvaluator();

    @Test
    void scoresCatalogEntitiesAgainstOnlySameTurnEvidenceAndReportsCoverage() {
        var turn = new NaturalBuyerConversationScenario.Turn(11, "precio Ranger", 
                NaturalBuyerConversationScenario.Expectation.CATALOG_FACT);
        var evidence = List.of("RANGER 2027 XLT GASOLINA 4X2", "$907,000 MXN", "185 hp",
                "https://ford.test/xlt");

        var result = evaluator.evaluateCatalog(turn,
                "Ranger 2027 XLT Gasolina 4x2 cuesta $907,000 MXN; entrega 185 hp. Fuente: https://ford.test/xlt",
                evidence);

        assertThat(result.supportedClaims()).contains("Ranger 2027 XLT Gasolina 4x2", "$907,000 MXN",
                "185 hp", "https://ford.test/xlt");
        assertThat(result.unsupportedClaims()).isEmpty();
        assertThat(result.unscoredClaims()).isEmpty();
        assertThat(result.precision()).isEqualTo("100%");
        assertThat(result.coverage()).isEqualTo("100%");

        var unsupported = evaluator.evaluateCatalog(turn, "La Ranger cuesta $999,000 MXN.", evidence);
        assertThat(unsupported.unsupportedClaims()).contains("$999,000 MXN");
        assertThat(unsupported.precision()).isEqualTo("0%");

        var unscored = evaluator.evaluateCatalog(turn, "Torque optimizado para una conducción más cómoda.", evidence);
        assertThat(unscored.unscoredClaims()).contains("Torque optimizado para una conducción más cómoda");
        assertThat(unscored.supportedClaims()).isEmpty();
        assertThat(unscored.precision()).isEqualTo("N/A");
        assertThat(unscored.coverage()).isEqualTo("0%");
    }

    @Test
    void scoresExplicitMemoryRecallOnlyFromBuyerHistoryAndReportsOmissions() {
        var turn = new NaturalBuyerConversationScenario.Turn(67,
                "¿Qué uso te comenté para la camioneta entre semana y los fines de semana?",
                NaturalBuyerConversationScenario.Expectation.MEMORY_RECALL,
                List.of("weekday_work", "weekend_family"));
        var buyerHistory = List.of(
                "La usaría para ir al trabajo entre semana y salir con mi familia los fines de semana.");

        var supported = evaluator.evaluateMemory(turn,
                "Dijiste que la usarías para ir al trabajo entre semana y salir con tu familia los fines de semana.",
                buyerHistory);
        assertThat(supported.supportedClaims()).containsExactly("weekday_work", "weekend_family");
        assertThat(supported.unsupportedClaims()).isEmpty();
        assertThat(supported.recallMisses()).isEmpty();

        var assistantOnly = evaluator.evaluateMemory(turn,
                "Dijiste que trabajas entre semana y sales con tu familia los fines de semana.",
                List.of());
        assertThat(assistantOnly.unsupportedClaims()).contains("weekday_work", "weekend_family");

        var contradictory = evaluator.evaluateMemory(turn,
                "Dijiste que la usarías para ir a la escuela entre semana y salir con amigos los fines de semana.",
                buyerHistory);
        assertThat(contradictory.unsupportedClaims()).contains("weekday_work", "weekend_family");

        var omitted = evaluator.evaluateMemory(turn, "Solo mencionaste salir con tu familia los fines de semana.",
                buyerHistory);
        assertThat(omitted.recallMisses()).containsExactly("weekday_work");

        var fabricated = evaluator.evaluateMemory(turn,
                "Dijiste que la usarías para el trabajo entre semana y llevar bicicletas.", buyerHistory);
        assertThat(fabricated.supportedClaims()).contains("weekday_work");
        assertThat(fabricated.unsupportedClaims()).contains("bicycles");

        assertIllegalMutation(supported);
    }

    private static void assertIllegalMutation(LocalReplayPrecisionEvaluator.PrecisionResult result) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> result.supportedClaims().add("forged"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
