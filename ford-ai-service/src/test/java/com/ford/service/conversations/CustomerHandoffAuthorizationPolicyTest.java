package com.ford.service.conversations;

import com.ford.service.conversations.application.CustomerHandoffAuthorizationPolicy;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerHandoffAuthorizationPolicyTest {
    private final CustomerHandoffAuthorizationPolicy policy = new CustomerHandoffAuthorizationPolicy();

    @Test
    void directCustomerRequestAuthorizesWithoutPriorOffer() {
        assertThat(policy.isAuthorized("Quiero hablar con un especialista de Ford, por favor.", memory()))
                .isTrue();
    }

    @Test
    void buyingIntentDoesNotAuthorizeHandoffInSameTurn() {
        assertThat(policy.isAuthorized("Estoy listo para comprar una Territory este mes.", memory()))
                .isFalse();
    }

    @Test
    void laterAffirmativeAuthorizesOnlyAfterAssistantOffer() {
        var offered = memory("Sí", message(ConversationMessage.Direction.OUTBOUND,
                "¿Desea que le conecte con un especialista Ford?"));
        assertThat(policy.isAuthorized("Sí, quiero que me conectes con un especialista.", offered)).isTrue();
        assertThat(policy.isAuthorized("Sí", memory())).isFalse();
    }

    @Test
    void worksWithTheProductionMemoryProjectionThatExcludesTheCurrentInboundTurn() {
        var priorAssistantOffer = memory(message(ConversationMessage.Direction.OUTBOUND,
                "¿Desea que le conecte con un especialista Ford?"));
        assertThat(policy.isAuthorized("Sí", priorAssistantOffer)).isTrue();
    }

    @Test
    void priorYesAndAssistantOwnOfferAreNotConsent() {
        var priorYes = memory(
                message(ConversationMessage.Direction.INBOUND, "Sí"),
                message(ConversationMessage.Direction.OUTBOUND, "¿Quiere hablar con un asesor?") );
        assertThat(policy.isAuthorized("Estoy listo para comprar.", priorYes)).isFalse();
    }

    @Test
    void declineOverridesARequestedHandoff() {
        var offered = memory("No, prefiero seguir aquí.", message(ConversationMessage.Direction.OUTBOUND,
                "¿Desea que le conecte con un especialista?") );
        assertThat(policy.isAuthorized("No, prefiero seguir aquí.", offered)).isFalse();
    }

    @Test
    void ordinaryNegationDoesNotCountAsDecliningAnOffer() {
        assertThat(policy.isDecline("No tengo prisa; me interesa una Ranger." )).isFalse();
        assertThat(policy.isDecline("No, gracias." )).isTrue();
    }

    @Test
    void aResolvedQuestionDoesNotAuthorizeHandoff() {
        assertThat(policy.isAuthorized("Gracias, ya entendí la oferta.", memory())).isFalse();
    }

    private static ConversationMemoryContext memory(ConversationMessage... messages) {
        return memory(null, messages);
    }

    private static ConversationMemoryContext memory(String current, ConversationMessage... messages) {
        var turns = new java.util.ArrayList<>(List.of(messages));
        if (current != null) turns.add(message(ConversationMessage.Direction.INBOUND, current));
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(
                UUID.randomUUID(), com.ford.service.channels.domain.Channel.WHATSAPP,
                "test", "customer", UUID.randomUUID());
        return new ConversationMemoryContext(key, "", turns, List.of(), 1);
    }

    private static ConversationMessage message(ConversationMessage.Direction direction, String text) {
        return new ConversationMessage(UUID.randomUUID(), UUID.randomUUID(), direction,
                UUID.randomUUID().toString(), text, Instant.now());
    }
}
