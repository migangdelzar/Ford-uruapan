package com.ford.service.ai;

import com.ford.service.ai.application.AssistantResponseQualityPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantResponseQualityPolicyTest {

    private final AssistantResponseQualityPolicy policy = new AssistantResponseQualityPolicy();

    @Test
    void rejectsAnEmptyProviderResponse() {
        var verdict = policy.evaluate("¿Qué precio tiene la Ranger?", null, "  ");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("empty_response");
    }

    @Test
    void rejectsRawToolArgumentsInsteadOfSendingThemToTheCustomer() {
        var verdict = policy.evaluate("¿Qué versión tiene la Lobo?", null,
                "{\"query\":\"Ford Lobo\",\"modelYear\":2026}");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("internal_payload");
    }

    @Test
    void rejectsStructuredJsonEvenWhenItHasNoKnownToolField() {
        var verdict = policy.evaluate("¿Qué modelos hay?", null,
                "[\"Ranger\",\"Lobo\"]");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("internal_payload");
    }

    @Test
    void rejectsInternalToolNamesAndProviderDetails() {
        var verdict = policy.evaluate("¿Qué promociones hay?", null,
                "Consulté search_vehicles usando openrouter.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("internal_payload");
    }

    @Test
    void rejectsARepeatedAssistantAnswerFromMemory() {
        var memory = "Historial reciente:\n- asistente: La Ranger está disponible para revisión.\n"
                + "- usuario: ¿Y la Lobo?";

        var verdict = policy.evaluate("¿Y la Lobo?", memory,
                "La Ranger está disponible para revisión.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("repeated_response");
    }

    @Test
    void rejectsAMateriallyRepeatedAnswerWithOnlyMinorWordingChanges() {
        var memory = "Historial reciente:\n- asistente: La Ranger está disponible en la agencia Ford Uruapan.";

        var verdict = policy.evaluate("¿Sigue disponible?", memory,
                "La Ranger está disponible en la agencia Ford Uruapan actualmente.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("repeated_response");
    }

    @Test
    void rejectsTheGenericScopeReplyWhenTheCustomerAskedARealQuestion() {
        var verdict = policy.evaluate("¿Cuántos kilómetros por litro da la Raptor?", null,
                "Con mucho gusto le ayudo con vehículos Ford. ¿Qué modelo le interesa revisar?");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("generic_scope_response");
    }

    @Test
    void rejectsRepliesThatAskMoreThanOneQuestion() {
        var verdict = policy.evaluate("Me interesa el vehículo", null,
                "¿Qué modelo le interesa? ¿Y qué precio busca?");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("multiple_questions");
        assertThat(policy.repairInstruction(verdict.reason())).contains("una sola pregunta");
    }

    @Test
    void rejectsAQuestionThatCombinesTwoLeadDetails() {
        var verdict = policy.evaluate("Me interesa cotizar", null,
                "¿Qué modelo le interesa y qué precio aproximado busca?");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("multiple_questions");
    }

    @Test
    void acceptsAConciseGroundedSpanishAnswer() {
        var verdict = policy.evaluate("¿Cuántos kilómetros por litro da la Raptor?", null,
                "La ficha disponible indica un rendimiento aproximado de 7.1 km/l. Puede variar según el uso.");

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.reason()).isEqualTo("accepted");
    }
}
