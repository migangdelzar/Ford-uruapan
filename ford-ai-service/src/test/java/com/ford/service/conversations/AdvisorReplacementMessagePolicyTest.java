package com.ford.service.conversations;

import com.ford.service.conversations.application.AdvisorReplacementMessagePolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisorReplacementMessagePolicyTest {
    private final AdvisorReplacementMessagePolicy policy = new AdvisorReplacementMessagePolicy();

    @Test
    void permitsStylisticEditThatAddsNoUnsupportedClaim() {
        var verdict = policy.validate("La Bronco Sport 2025 cuesta $700,000. Fuente: https://ford.mx/modelo",
                true, "¿Cuál es el precio de la Bronco Sport?",
                "Con gusto: la Bronco Sport 2025 cuesta $700,000. Fuente: https://ford.mx/modelo");
        assertThat(verdict.allowed()).isTrue();
    }

    @Test
    void rejectsNewNumbersModelsAndLinksNotInApprovedDraftOrCustomerMessage() {
        assertThat(policy.validate("Le comparto la información publicada.", true, "¿Qué precio tiene?",
                "La Bronco Sport cuesta $700,000. https://example.com").allowed()).isFalse();
    }

    @Test
    void rejectsAdvisorAuthoredDiscountInventoryAndFinanceCommitments() {
        assertThat(policy.validate("Puedo compartir información publicada.", true, "¿Tienen ofertas?",
                "Te aplicamos un descuento y la unidad está reservada.").allowed()).isFalse();
        assertThat(policy.validate("Puedo compartir información publicada.", true, "¿Hay financiamiento?",
                "Tu crédito ya está aprobado.").allowed()).isFalse();
    }

    @Test
    void permitsOnlyClaimFreeClarificationWhenNoSendableDraftExists() {
        assertThat(policy.validate(null, false, "Quiero información.",
                "¿Qué modelo o versión le interesa?").allowed()).isTrue();
        assertThat(policy.validate(null, false, "Quiero información.",
                "La Bronco Sport cuesta $700,000.").allowed()).isFalse();
    }
}
