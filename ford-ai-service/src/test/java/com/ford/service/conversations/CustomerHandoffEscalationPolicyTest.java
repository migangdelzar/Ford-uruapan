package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.CustomerHandoffEscalationPolicy;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerHandoffEscalationPolicyTest {

    private final CustomerHandoffEscalationPolicy policy = new CustomerHandoffEscalationPolicy();

    @Test
    void escalatesOnDirectHumanRequest() {
        assertThat(policy.shouldEscalate("Quiero hablar con un asesor por favor.", memory())).isTrue();
    }

    @Test
    void escalatesOnUpsetOrComplaintRegardlessOfModelDecision() {
        assertThat(policy.shouldEscalate("Estoy muy molesta por la atención que recibí.", memory())).isTrue();
        assertThat(policy.shouldEscalate("Quiero poner una queja formal.", memory())).isTrue();
    }

    @Test
    void escalatesOnSecondRefusalToShareQualificationDetails() {
        var prior = inbound("No quiero compartir mi nombre.");
        var current = "No quiero compartir mi nombre.";

        assertThat(policy.shouldEscalate(current, memory(prior))).isTrue();
    }

    @Test
    void doesNotEscalateOnFirstRefusalOrOrdinaryTurn() {
        assertThat(policy.shouldEscalate("No quiero compartir mi nombre.", memory())).isFalse();
        assertThat(policy.shouldEscalate("¿Qué promociones tienen?", memory())).isFalse();
    }

    private static ConversationMemoryContext memory(ConversationMessage... messages) {
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP, "eval",
                "synthetic-customer", UUID.randomUUID());
        return new ConversationMemoryContext(key, "", List.of(messages), List.of(), 1);
    }

    private static ConversationMessage inbound(String text) {
        return new ConversationMessage(UUID.randomUUID(), UUID.randomUUID(),
                ConversationMessage.Direction.INBOUND, UUID.randomUUID().toString(), text, Instant.now());
    }
}
