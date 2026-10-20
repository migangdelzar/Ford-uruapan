package com.ford.service.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisorResponseRepeatPolicyTest {

    private final AdvisorResponseRepeatPolicy policy = new AdvisorResponseRepeatPolicy();

    @Test
    void rejectsExactReplyAfterPunctuationWhitespaceAndAccentNormalization() {
        var verdict = policy.evaluate("¿Qué versión recomienda?",
                List.of("Le comparto información de la promoción vigente."),
                "Le comparto informacion de la promocion vigente!");

        assertThat(verdict.repeated()).isTrue();
        assertThat(verdict.reasonCode()).isEqualTo("exact_repeat");
    }

    @Test
    void rejectsHighOverlapAnswerWhenCurrentQuestionIsMateriallyDifferent() {
        var verdict = policy.evaluate("¿Cuál es el rendimiento de combustible?",
                List.of("La Ford Bronco está disponible en la agencia. Le comparto las versiones."),
                "La Ford Bronco está disponible actualmente en la agencia. Le comparto las versiones.");

        assertThat(verdict.repeated()).isTrue();
        assertThat(verdict.reasonCode()).isEqualTo("near_repeat");
    }

    @Test
    void permitsRepeatedConciseAnswerWhenCustomerRepeatsTheSameQuestion() {
        var verdict = policy.evaluate("¿Qué precio tiene la Bronco?", List.of("¿Qué precio tiene la Bronco?"),
                List.of("¿Qué precio tiene la Bronco?", "La Bronco inicia en el precio publicado."),
                "La Bronco inicia en el precio publicado.");

        assertThat(verdict.repeated()).isFalse();
    }

    @Test
    void rejectsSameGenericAdvisorReplyForMateriallyDifferentQuestions() {
        var verdict = policy.evaluate("¿Cuántos kilómetros por litro da la Raptor?",
                List.of("¿Qué promociones tienen para la Ranger?", "Con gusto le ayudo. ¿Qué modelo le interesa revisar?"),
                "Con mucho gusto le ayudo. ¿Qué modelo le interesa revisar?");

        assertThat(verdict.repeated()).isTrue();
    }

    @Test
    void doesNotRejectDistinctAnswersForSharedGreetingOrHandoffSuffix() {
        var verdict = policy.evaluate("¿Cuánto rinde la Bronco?",
                List.of("Hola, con gusto. La Bronco rinde hasta 12 km/l. Si gusta, le apoyo."),
                "Hola, con gusto. La Bronco tiene motor EcoBoost. Si gusta, le apoyo.");

        assertThat(verdict.repeated()).isFalse();
    }

    @Test
    void distinguishesSyntheticThreeTurnRepeatSequence() {
        var first = "Para conocer los detalles, le recomiendo visitar la agencia Ford. ¿Desea que le ayude con algo más?";
        var verdict = policy.evaluate("¿Qué consumo tiene la Ranger?",
                List.of(first, "¿Qué promociones hay?", first), first);

        assertThat(verdict.repeated()).isTrue();
    }
}
