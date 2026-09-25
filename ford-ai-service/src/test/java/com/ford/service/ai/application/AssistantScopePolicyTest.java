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
}
