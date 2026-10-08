package com.ford.service.conversations;

import com.ford.service.conversations.application.SpecialistHandoffPayloadFactory;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecialistHandoffPayloadFactoryTest {
    private final SpecialistHandoffPayloadFactory factory = new SpecialistHandoffPayloadFactory();

    @Test
    void payloadUsesOnlyCustomerGroundedKnownFieldsAndOmitsUnknownValues() {
        var memory = memory(
                inbound("Estoy buscando una Ranger para viajar con mi familia y quiero saber qué opciones hay."),
                outbound("¿Desea que le conecte con un especialista Ford?"));
        var payload = factory.create("Sí, quiero que me conectes con un especialista.", memory,
                "Se registró la solicitud.");

        assertThat(payload.reason()).contains("quiero que me conectes");
        assertThat(payload.vehicle()).contains("Ranger");
        assertThat(payload.needs()).contains("para viajar con mi familia");
        assertThat(payload.unresolvedQuestion()).isEmpty();
        assertThat(payload.arguments()).containsKeys("reason", "vehicle", "needs")
                .doesNotContainKeys("unresolvedQuestion", "name", "phone", "email", "null");
    }

    @Test
    void includesAnUnresolvedCustomerQuestionOnlyWhenInformationAnswerConfirmsUncertainty() {
        var message = inbound("¿Sigue vigente la oferta EXPEDITION? ¿Hay una fecha comprobable?");
        var memory = memory(message);
        var unresolved = factory.create(message.content(), memory, "La vigencia no está confirmada.");
        var resolved = factory.create(message.content(), memory, "La oferta es válida hasta 2026-10-31.");

        assertThat(unresolved.unresolvedQuestion()).contains("¿Sigue vigente la oferta EXPEDITION?");
        assertThat(resolved.unresolvedQuestion()).isEmpty();
        assertThat(unresolved.vehicle()).contains("EXPEDITION");
    }

    @Test
    void preservesTheExactKnownModelVariantFromCustomerHistory() {
        var text = "Quiero una RANGER RAPTOR para trabajo de campo.";
        var payload = factory.create(text, memory(inbound(text)), "Solicitud registrada.");
        assertThat(payload.vehicle()).contains("RANGER RAPTOR");
        assertThat(payload.needs()).contains("para trabajo de campo");
    }

    @Test
    void typedPayloadRejectsNullAndUnsupportedOversizedValues() {
        assertThatNullPointerException().isThrownBy(() -> new com.ford.service.conversations.api.SpecialistHandoffPayload(
                "Motivo", null, java.util.Optional.empty(), java.util.Optional.empty()));
        assertThatThrownBy(() -> new com.ford.service.conversations.api.SpecialistHandoffPayload(
                "Motivo", java.util.Optional.of("x".repeat(101)), java.util.Optional.empty(),
                java.util.Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removesUnrequestedEmailAndPhoneFromFreeTextFields() {
        var payload = factory.create("Quiero hablar con asesor. Mi correo es ana@example.com y mi teléfono 452 123 4567.",
                memory(), "Registré la solicitud.");
        assertThat(payload.arguments().toString()).doesNotContain("ana@example.com", "452 123 4567");
    }

    private static ConversationMemoryContext memory(ConversationMessage... messages) {
        var key = new ConversationMemoryKey(UUID.randomUUID(), com.ford.service.channels.domain.Channel.WHATSAPP,
                "eval", "synthetic-customer", UUID.randomUUID());
        return new ConversationMemoryContext(key, "", List.of(messages), List.of(), 1);
    }

    private static ConversationMessage inbound(String text) { return message(ConversationMessage.Direction.INBOUND, text); }
    private static ConversationMessage outbound(String text) { return message(ConversationMessage.Direction.OUTBOUND, text); }
    private static ConversationMessage message(ConversationMessage.Direction direction, String text) {
        return new ConversationMessage(UUID.randomUUID(), UUID.randomUUID(), direction, UUID.randomUUID().toString(),
                text, Instant.now());
    }
}
