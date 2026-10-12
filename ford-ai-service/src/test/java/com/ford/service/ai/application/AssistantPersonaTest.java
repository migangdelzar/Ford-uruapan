package com.ford.service.ai.application;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPersonaTest {

    private final AssistantPersona persona = new AssistantPersona();

    @Test
    void keepsTheMexicanAssistantNameStableForOneConversationAndVariesAcrossNewConversations() {
        var firstConversation = new UUID(0, 0);
        var secondConversation = new UUID(0, 1);

        var firstName = persona.nameFor(firstConversation);

        assertThat(firstName).isEqualTo(persona.nameFor(firstConversation));
        assertThat(firstName).isNotEqualTo(persona.nameFor(secondConversation));
        assertThat(AssistantPersona.APPROVED_NAMES).contains(firstName, persona.nameFor(secondConversation));
    }

    @Test
    void introducesTheAssistantByAConsistentFriendlyName() {
        assertThat(persona.introductionFor(new UUID(0, 0)))
                .isEqualTo("Hola, soy *Ana*, su asistente de Ford Uruapan. 👋");
    }
}
