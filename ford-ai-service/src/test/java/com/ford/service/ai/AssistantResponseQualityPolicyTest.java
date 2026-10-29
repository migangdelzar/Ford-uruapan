package com.ford.service.ai;

import com.ford.service.ai.application.AssistantResponseQualityPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void rejectsEveryCatalogCallbackNameInCustomerFacingText() {
        var callbackNames = List.of("search_vehicles", "search_vehicle_and_offers", "get_vehicle",
                "compare_vehicles", "search_offers", "list_promotions", "get_vehicle_provenance",
                "get_catalog_status", "get_page_links", "compare_trims", "compare_model_years",
                "get_variant_comparisons");

        for (var callbackName : callbackNames) {
            var verdict = policy.evaluate("Pregunta", null, "Detalle interno: " + callbackName);
            assertThat(verdict.accepted()).as(callbackName).isFalse();
            assertThat(verdict.reason()).as(callbackName).isEqualTo("internal_payload");
        }
    }

    @Test
    void leavesRepeatDetectionToTheAlwaysOnProviderNeutralGuard() {
        var memory = "Historial reciente:\n- asistente: La Ranger está disponible para revisión.\n"
                + "- usuario: ¿Y la Lobo?";

        var verdict = policy.evaluate("¿Y la Lobo?", memory,
                "La Ranger está disponible para revisión.");

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void doesNotRunOptionalSimilarityDetectionForRepeatedAnswers() {
        var memory = "Historial reciente:\n- asistente: La Ranger está disponible en la agencia Ford Uruapan.";

        var verdict = policy.evaluate("¿Sigue disponible?", memory,
                "La Ranger está disponible en la agencia Ford Uruapan actualmente.");

        assertThat(verdict.accepted()).isTrue();
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
    void writesRepairInstructionsAndRecoveryRepliesInFormalAddress() {
        assertThat(policy.repairInstruction("multiple_questions"))
                .startsWith("Responda directamente")
                .contains("solicite solo un dato");
        assertThat(policy.repairInstruction("repeated_response"))
                .startsWith("Redacte una respuesta nueva");
        assertThat(policy.repairInstruction("internal_payload"))
                .startsWith("Responda directamente");
        assertThat(policy.recoveryMessage("repeated_response"))
                .isEqualTo("No quiero repetirle la respuesta anterior. ¿Qué detalle desea que revise?");
        assertThat(policy.recoveryMessage("internal_payload"))
                .isEqualTo("No pude preparar una respuesta clara con la información disponible. "
                        + "Intente de nuevo en unos segundos.");
        assertThat(policy.recoveryMessage("empty_response"))
                .isEqualTo("No pude responderle en este momento. Intente de nuevo en unos segundos.");
    }

    @Test
    void logsCanUseTheValidationReasonWithoutIncludingCustomerOrGeneratedText() {
        var customerText = "¿Qué modelo me recomiendas? mi teléfono es 5551234567";
        var generatedText = "¿Qué modelo buscas y cuál es tu presupuesto?";

        var verdict = policy.evaluate(customerText, null, generatedText);

        assertThat(verdict.reason()).isEqualTo("multiple_questions");
        assertThat(verdict.reason()).doesNotContain(customerText, generatedText, "5551234567");
    }

    @Test
    void rejectsAQuestionThatCombinesTwoLeadDetails() {
        var verdict = policy.evaluate("Me interesa cotizar", null,
                "¿Qué modelo le interesa y qué precio aproximado busca?");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("multiple_questions");
    }

    @Test
    void acceptsOneQuestionThatOffersAlternativesForTheSameDetail() {
        var verdict = policy.evaluate("Busco una SUV", null,
                "¿Prefiere la Ford Escape o la Bronco Sport?");

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.reason()).isEqualTo("accepted");
    }

    @Test
    void rejectsOneQuestionThatRequestsTwoDifferentDetails() {
        var verdict = policy.evaluate("Quiero cotizar", null,
                "¿Qué modelo le interesa y cuál es su presupuesto?");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("multiple_questions");
    }

    @Test
    void exposesOnlyStableReasonCodesForDiagnostics() {
        var verdict = policy.evaluate("private customer prompt", null,
                "¿Qué modelo buscas y cuál es tu presupuesto?");

        assertThat(policy.diagnosticReason(verdict)).isEqualTo("multiple_questions");
        assertThat(policy.diagnosticReason(null)).isEqualTo("accepted");
    }

    @Test
    void acceptsAConciseGroundedSpanishAnswer() {
        var verdict = policy.evaluate("¿Cuántos kilómetros por litro da la Raptor?", null,
                "La ficha disponible indica un rendimiento aproximado de 7.1 km/l. Puede variar según el uso.");

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.reason()).isEqualTo("accepted");
    }

    @Test
    void rejectsResponsesThatExceedTheCustomerReplyLimit() {
        var verdict = policy.evaluate("¿Qué versiones hay?", null, "a".repeat(1601));

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("response_too_long");
        assertThat(policy.repairInstruction(verdict.reason())).contains("breve");
    }
}
