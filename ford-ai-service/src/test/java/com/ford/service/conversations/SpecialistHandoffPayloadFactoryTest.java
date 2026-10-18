package com.ford.service.conversations;

import com.ford.service.conversations.application.SpecialistHandoffPayloadFactory;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.leads.domain.LeadJourneyFact;
import com.ford.service.leads.domain.LeadJourneyFactKey;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    @Test
    void packetUsesOnlyConfirmedJourneyFactsAndCountsCompleteness() {
        var journey = journey(Map.of(
                LeadJourneyFactKey.VEHICLE_MODEL, fact("Ranger"),
                LeadJourneyFactKey.PAYMENT_METHOD, fact("financiamiento"),
                LeadJourneyFactKey.DOWN_PAYMENT, fact("20 mil pesos"),
                LeadJourneyFactKey.PURCHASE_TIMING, fact("este mes"),
                LeadJourneyFactKey.CUSTOMER_NAME, fact("María Elena")));

        var packet = factory.createPacket(journey);

        assertThat(packet.completeness()).isEqualTo(5);
        assertThat(packet.partial()).isFalse();
        assertThat(packet.arguments()).containsEntry("vehicleModel", "Ranger")
                .containsEntry("paymentMethod", "financiamiento")
                .containsEntry("downPayment", "20 mil pesos")
                .containsEntry("purchaseTimeframe", "este mes")
                .containsEntry("customerName", "María Elena");
    }

    @Test
    void packetOmitsDownPaymentUnlessFinancingAndOmitsSensitiveValues() {
        var cash = journey(Map.of(
                LeadJourneyFactKey.PAYMENT_METHOD, fact("contado"),
                LeadJourneyFactKey.DOWN_PAYMENT, fact("20 mil pesos")));
        assertThat(factory.createPacket(cash).arguments()).doesNotContainKey("downPayment");

        var sensitive = journey(Map.of(LeadJourneyFactKey.CUSTOMER_NAME, fact("1234567890")));
        assertThat(factory.createPacket(sensitive).arguments()).doesNotContainKey("customerName");
    }

    @Test
    void emptyJourneyYieldsPartialPacket() {
        assertThat(factory.createPacket(journey(Map.of())).partial()).isTrue();
        assertThat(factory.createPacket(null).partial()).isTrue();
    }

    private static LeadJourneyFact fact(String value) {
        return new LeadJourneyFact(value, "provider-1", Instant.now());
    }

    private static LeadJourneySnapshot journey(Map<LeadJourneyFactKey, LeadJourneyFact> facts) {
        return new LeadJourneySnapshot(UUID.randomUUID(), UUID.randomUUID(), null, null, null, null,
                facts, null, 0, null, 0, Instant.now());
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
