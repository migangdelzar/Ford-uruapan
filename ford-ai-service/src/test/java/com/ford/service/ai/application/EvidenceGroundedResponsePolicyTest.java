package com.ford.service.ai.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGroundedResponsePolicyTest {

    private final EvidenceGroundedResponsePolicy policy = new EvidenceGroundedResponsePolicy();

    @Test
    void returnsTypedQualityOutcomesForSupportedUnsupportedAndUnsafeAnswers() {
        assertThat(policy.evaluateDecision("Ranger", "Ranger 2027", "Ranger 2027") .status())
                .isEqualTo(QualityDecision.Status.PASS);
        assertThat(policy.evaluateDecision("Ranger", "", "Ranger cuesta 500000") .status())
                .isEqualTo(QualityDecision.Status.REPAIR);
        assertThat(policy.evaluateDecision("¿Entrega?", "", "Le garantizo entrega inmediata") .status())
                .isEqualTo(QualityDecision.Status.BLOCK);
    }

    @Test
    void acceptsNumbersAndUrlsPresentInToolEvidence() {
        var evidence = "{\"price\":\"$650,000\",\"modelYear\":2024,\"url\":\"https://www.ford.mx/ranger\"}";

        var verdict = policy.evaluate("¿Cuánto cuesta la Ranger 2024?",
                evidence, "La Ranger 2024 tiene un precio de lista de $650,000. Detalles en https://www.ford.mx/ranger");

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.reason()).isEqualTo("accepted");
    }

    @Test
    void acceptsEvidenceSupportedPublishedPriceResponse() {
        var evidence = "{\"publishedPrice\":\"$650,000\",\"model\":\"Ranger\",\"trim\":\"XLT\"}";

        var verdict = policy.evaluate("¿Cuál es el precio publicado de la Ranger XLT?", evidence,
                "El precio publicado de la Ranger XLT es $650,000.");

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.reason()).isEqualTo("accepted");
    }

    @Test
    void rejectsPriceNotPresentInEvidence() {
        var evidence = "{\"price\":\"$650,000\"}";

        var verdict = policy.evaluate("¿Cuánto cuesta?", evidence,
                "La Ranger tiene un precio de lista de $599,000.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("unsupported_fact_claim");
    }

    @Test
    void rejectsModelYearNotPresentInEvidence() {
        var evidence = "{\"modelYear\":2023}";

        var verdict = policy.evaluate("¿De qué año es?", evidence,
                "El modelo disponible es de año 2026 con la garantía vigente.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("unsupported_fact_claim");
    }

    @Test
    void acceptsNumberSuppliedByTheCustomer() {
        var evidence = "{}";

        var verdict = policy.evaluate("Tengo un enganche de 50000 pesos", evidence,
                "Con un enganche de 50000 pesos revisamos las opciones disponibles.");

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void rejectsUrlNotPresentInEvidence() {
        var evidence = "{\"url\":\"https://www.ford.mx/ranger\"}";

        var verdict = policy.evaluate("¿Tienen más información?", evidence,
                "Puede verlo en https://promo-externa.example.com/ranger");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("unsupported_url");
    }

    @Test
    void rejectsDeliveryPromise() {
        var verdict = policy.evaluate("¿Cuándo me la entregan?", "{}",
                "Le garantizo entrega inmediata hoy mismo.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("never_promise");
    }

    @Test
    void rejectsCreditApprovalLanguage() {
        var verdict = policy.evaluate("¿Ya me autorizaron el crédito?", "{}",
                "Su crédito ya fue aprobado por el área financiera.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("never_promise");
    }

    @Test
    void acceptsQualifiedAnswerWithoutClaims() {
        var verdict = policy.evaluate("¿Qué incluye la garantía?", "garantía cobertura 3 años o 60000 km",
                "La garantía cubre componentes mecánicos según la documentación aprobada.");

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void rejectsUnsupportedSingleDigitClaims() {
        var verdict = policy.evaluate("¿Cuántas versiones hay?", "{}",
                "Tenemos 3 grupos disponibles para revisar.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("unsupported_fact_claim");
    }

    @Test
    void doesNotAcceptPartialNumericMatches() {
        var verdict = policy.evaluate("¿Cuánto cuesta?", "{\"price\":650000}",
                "El precio es de $500.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("unsupported_fact_claim");
    }

    @Test
    void rejectsVehicleModelNamesNotMentionedByCustomerOrEvidence() {
        var verdict = policy.evaluate("¿Qué precio tiene?", "{\"model\":\"Ranger\"}",
                "La Mustang tiene una cabina amplia.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("unsupported_model_name");
    }

    @Test
    void repairsUnsupportedModelClaimsWithoutReplacingThemWithAnotherModel() {
        var instruction = policy.repairInstruction("unsupported_model_name");

        assertThat(instruction)
                .contains("No menciones ningún modelo Ford")
                .contains("Elimina el nombre no confirmado")
                .contains("pregunta cuál quiso decir")
                .doesNotContain("Ranger", "Mustang", "Bronco");
    }

    @Test
    void acceptsVehicleModelNamesConfirmedByCustomerOrEvidence() {
        var evidence = "{\"model\":\"Ranger\",\"sourceUrl\":\"https://ford.mx/ranger\"}";

        assertThat(policy.evaluate("¿Qué precio tiene la Ranger?", evidence,
                "La Ranger está en el catálogo.").accepted()).isTrue();
        assertThat(policy.evaluate("¿Qué precio tiene?", evidence,
                "La Ranger está en el catálogo.").accepted()).isTrue();
    }

    @Test
    void doesNotTreatBaseModelAsEvidenceForADifferentVariant() {
        var verdict = policy.evaluate("¿Qué precio tiene?", "{\"model\":\"Ranger\"}",
                "La Ranger Raptor está en el catálogo.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("unsupported_model_name");
    }

    @Test
    void missingEngineDisplacementCannotBeFilledFromModelMemory() {
        var evidence = "Ranger 2027 XLT Gasolina 4x2. "
                + "Transmisión automática de 10 velocidades.";
        var verdict = policy.evaluate("¿Y de esa versión, qué motor tiene?", evidence,
                "Tiene motor 2.3 EcoBoost.");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("unsupported_fact_claim");
    }
}
