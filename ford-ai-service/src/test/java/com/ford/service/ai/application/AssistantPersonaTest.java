package com.ford.service.ai.application;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPersonaTest {

    private final AssistantPersona persona = new AssistantPersona();

    @Test
    void usesTheSameAssistantNameAcrossConversations() {
        var firstConversation = new UUID(0, 0);
        var secondConversation = new UUID(0, 1);

        assertThat(persona.nameFor(firstConversation)).isEqualTo("MÍA");
        assertThat(persona.nameFor(secondConversation)).isEqualTo("MÍA");
    }

    @Test
    void introducesTheVirtualAssistantWithFormalAddress() {
        assertThat(persona.introductionFor(new UUID(0, 0)))
                .isEqualTo("Hola, soy *MÍA*, su asistente virtual de Ford Uruapan. 👋");
    }
}
