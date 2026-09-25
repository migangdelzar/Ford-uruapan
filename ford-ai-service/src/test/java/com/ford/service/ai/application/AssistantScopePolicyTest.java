package com.ford.service.ai.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantScopePolicyTest {

    private final AssistantScopePolicy policy = new AssistantScopePolicy(new IntentRouter());

    @Test
    void allowsFordVehicleQuestions() {
        assertThat(policy.allows("¿Qué precio tiene la Ford Ranger?", null)).isTrue();
    }

    @Test
    void allowsInformationalFinanceQuestions() {
        assertThat(policy.allows("¿Cuál es el enganche?", null)).isTrue();
    }

    @Test
    void allowsGreetings() {
        assertThat(policy.allows("Hola, buenos días", null)).isTrue();
    }

    @Test
    void rejectsClearlyUnrelatedRequests() {
        assertThat(policy.allows("Escribe un poema sobre volcanes", null)).isFalse();
    }

    @Test
    void doesNotTrustAConflictingModelIntentToExpandTheScope() {
        var financeDecision = new ConversationDecision(
                ConversationIntent.FINANCE, LeadTemperature.WARM, LeadStage.QUALIFYING,
                HandoffRecommendation.NONE, ToolFamily.FINANCE_ESTIMATE,
                SensitiveDataLevel.NONE, java.util.List.of());

        assertThat(policy.allows("Escribe un poema sobre volcanes", financeDecision)).isFalse();
    }

    @Test
    void allowsExplicitHumanRequestsToReachTheHandoffPolicy() {
        assertThat(policy.allows("Quiero hablar con una persona", null)).isTrue();
    }

    @Test
    void allowsAContextualVehicleFollowUpEvenWhenTheCurrentMessageOmitsTheModel() {
        assertThat(policy.allows("¿Cuántos kilómetros por litro da?", null,
                "Historial reciente:\n- asistente: Ford Lobo Raptor 2026\n- usuario: Lobo Raptor"))
                .isTrue();
    }

    @Test
    void allowsCommonVehicleTechnicalTermsWithoutRequiringTheBrandAgain() {
        assertThat(policy.allows("¿Qué rendimiento tiene la Raptor?", null)).isTrue();
    }

    @Test
    void allowsShortEnglishGreetingsToReachTheAssistant() {
        assertThat(policy.allows("Hi", null)).isTrue();
    }

    @Test
    void allowsAClarifyingQuestionWithoutAutomotiveKeywords() {
        assertThat(policy.allows("¿Me puedes explicar eso mejor?", null)).isTrue();
    }
}
