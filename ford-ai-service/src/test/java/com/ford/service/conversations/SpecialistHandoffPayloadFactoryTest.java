package com.ford.service.conversations;

import com.ford.service.conversations.application.SpecialistHandoffPayloadFactory;
import com.ford.service.conversations.api.InternalHandoffNote;
import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.VehicleEntityResolver;
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
    void boundsLongReasonToDatabaseCapacityAfterContactRedaction() {
        var message = "Me llamo Ana López. Contacto ana@example.com, 452 123 4567. "
                + "Quiero hablar con un asesor sobre una Ranger para viajar con mi familia. ".repeat(5);

        var payload = factory.create(message, memory(), "Solicitud registrada.");

        assertThat(payload.reason()).hasSize(80)
                .startsWith("Solicitud del cliente: «").endsWith("»")
                .contains("[dato de contacto omitido]");
        assertThat(payload.arguments().toString()).doesNotContain("Ana López", "ana@example.com", "452 123 4567");
    }

    @Test
    void removesUnrequestedEmailAndPhoneFromFreeTextFields() {
        var payload = factory.create("Quiero hablar con asesor. Mi correo es ana@example.com y mi teléfono 452 123 4567.",
                memory(), "Registré la solicitud.");
        assertThat(payload.arguments().toString()).doesNotContain("ana@example.com", "452 123 4567");

        var dottedPhone = factory.create("Quiero hablar con asesor. Mi teléfono es 452.123.4567.",
                memory(), "Registré la solicitud.");
        assertThat(dottedPhone.arguments().toString()).doesNotContain("452.123.4567");
    }

    @Test
    void packetUsesConfirmedShoppingFactsAndNeverEmitsLegacyFinanceFacts() {
        var journey = journey(Map.of(
                LeadJourneyFactKey.VEHICLE_MODEL, fact("Ranger"),
                LeadJourneyFactKey.VEHICLE_TRIM, fact("XLT"),
                LeadJourneyFactKey.VEHICLE_USE, fact("para viajar con mi familia"),
                LeadJourneyFactKey.PAYMENT_METHOD, fact("financiamiento"),
                LeadJourneyFactKey.DOWN_PAYMENT, fact("20 mil pesos"),
                LeadJourneyFactKey.PURCHASE_TIMING, fact("este mes"),
                LeadJourneyFactKey.BUDGET_RANGE, fact("500 a 600 mil pesos"),
                LeadJourneyFactKey.TRADE_IN_INTEREST, fact("sí"),
                LeadJourneyFactKey.APPOINTMENT_INTEREST, fact("sí"),
                LeadJourneyFactKey.CUSTOMER_NAME, fact("María Elena")));

        var packet = factory.createPacket(journey, "Me llamo María Elena.");

        assertThat(packet.completeness()).isEqualTo(5);
        assertThat(packet.partial()).isFalse();
        assertThat(packet.arguments()).containsEntry("vehicleModel", "Ranger")
                .containsEntry("vehicleTrim", "XLT")
                .containsEntry("needs", "para viajar con mi familia")
                .containsEntry("purchaseTimeframe", "este mes")
                .containsEntry("buyerPriceRange", "500 a 600 mil pesos")
                .containsEntry("tradeInInterest", "sí")
                .containsEntry("appointmentInterest", "sí")
                .containsEntry("customerName", "María Elena")
                .doesNotContainKeys("paymentMethod", "downPayment");
    }

    @Test
    void packetOmitsDownPaymentUnlessFinancingAndOmitsSensitiveValues() {
        var cash = journey(Map.of(
                LeadJourneyFactKey.PAYMENT_METHOD, fact("contado"),
                LeadJourneyFactKey.DOWN_PAYMENT, fact("20 mil pesos")));
        assertThat(factory.createPacket(cash).arguments()).doesNotContainKey("downPayment");

        var sensitive = journey(Map.of(LeadJourneyFactKey.CUSTOMER_NAME, fact("1234567890")));
        assertThat(factory.createPacket(sensitive, "Mi teléfono es 1234567890").arguments()).doesNotContainKey("customerName");
    }

    @Test
    void emptyJourneyYieldsPartialPacket() {
        assertThat(factory.createPacket(journey(Map.of())).partial()).isTrue();
        assertThat(factory.createPacket(null).partial()).isTrue();
    }

    @Test
    void packetFindsExplicitNameFromEarlierInboundTurnWhenCurrentTurnAuthorizesHandoff() {
        var memory = memory(inbound("Me llamo Ana López."),
                outbound("¿Desea que le conecte con un especialista Ford?"));

        var packet = factory.createPacket(journey(Map.of()), "Sí, por favor.", memory);

        assertThat(packet.arguments()).containsEntry("customerName", "Ana López");
    }

    @Test
    void packetRemovesExtractedNameFromConfirmedJourneyNeedsOnly() {
        var journey = journey(Map.of(LeadJourneyFactKey.VEHICLE_USE,
                fact("viajar con Ana López y mi familia")));

        var packet = factory.createPacket(journey, "Me llamo Ana López.");

        assertThat(packet.arguments()).containsEntry("customerName", "Ana López");
        assertThat(packet.needs().orElse("")).contains("viajar con");
        assertThat(packet.arguments().entrySet()).filteredOn(entry -> !entry.getKey().equals("customerName"))
                .allSatisfy(entry -> assertThat(entry.getValue()).doesNotContain("Ana López"));
    }

    @Test
    void explicitNamePhraseStopsBeforeFollowingRequest() {
        var packet = factory.createPacket(journey(Map.of()), "Me llamo Ana y busco una Ranger.");

        assertThat(packet.arguments()).containsEntry("customerName", "Ana");
    }

    @Test
    void explicitThreePartNameIsFullyCapturedAndRemovedFromSummary() {
        var text = "Me llamo Ana María López y quiero hablar con un asesor.";
        var packet = factory.createPacket(journey(Map.of()), text);
        var payload = factory.create(text, memory(inbound(text)), "Solicitud registrada.");

        assertThat(packet.arguments()).containsEntry("customerName", "Ana María López");
        assertThat(payload.reason()).doesNotContain("Ana María López", "López");
        assertThat(payload.needs().orElse("")).doesNotContain("Ana María López", "López");
    }

    @Test
    void shortNameReplyAcceptedOnlyAfterAssistantAskedWhatNameToShare() {
        var prompted = factory.createPacket(journey(Map.of()), "Ana López",
                memory(outbound("¿Qué nombre te gustaría que comparta?")));
        var arbitrary = factory.createPacket(journey(Map.of()), "Ana López", memory(outbound("¿Desea asesoría?")));

        assertThat(prompted.arguments()).containsEntry("customerName", "Ana López");
        assertThat(arbitrary.arguments()).doesNotContainKey("customerName");
    }

    @Test
    void explicitNameInCurrentRequestNeverEntersReasonOrOtherFreeTextFields() {
        var payload = factory.create("Me llamo Ana López y quiero hablar con un asesor.", memory(),
                "No se pudo confirmar la disponibilidad.");

        assertThat(payload.reason()).doesNotContain("Ana López");
        assertThat(payload.vehicle().orElse("")).doesNotContain("Ana López");
        assertThat(payload.needs().orElse("")).doesNotContain("Ana López");
        assertThat(payload.unresolvedQuestion().orElse("")).doesNotContain("Ana López");
    }

    @Test
    void promptedNameReplyNeverEntersReasonAfterAffirmativeHandoff() {
        var memory = memory(inbound("Me llamo Ana López."),
                outbound("¿Qué nombre te gustaría que comparta?"));
        var payload = factory.create("Ana López", memory, "No se pudo confirmar la disponibilidad.");
        var packet = factory.createPacket(journey(Map.of()), "Ana López", memory);

        assertThat(payload.reason()).doesNotContain("Ana López");
        assertThat(payload.vehicle().orElse("")).doesNotContain("Ana López");
        assertThat(payload.needs().orElse("")).doesNotContain("Ana López");
        assertThat(payload.unresolvedQuestion().orElse("")).doesNotContain("Ana López");
        assertThat(packet.arguments()).containsEntry("customerName", "Ana López");
    }

    @Test
    void earlierPromptedNameReplySurvivesLaterAffirmativeWithoutEnteringSummary() {
        var memory = memory(outbound("¿Qué nombre te gustaría que comparta?"),
                inbound("Ana López"),
                outbound("¿Desea que le conecte con un especialista Ford?"));

        var packet = factory.createPacket(journey(Map.of()), "Sí, por favor.", memory);
        var payload = factory.create("Sí, por favor.", memory, "Solicitud registrada.");

        assertThat(packet.arguments()).containsEntry("customerName", "Ana López");
        assertThat(payload.reason()).doesNotContain("Ana López");
        assertThat(payload.vehicle().orElse("")).doesNotContain("Ana López");
        assertThat(payload.needs().orElse("")).doesNotContain("Ana López");
        assertThat(payload.unresolvedQuestion().orElse("")).doesNotContain("Ana López");
    }

    @Test
    void laterNameRefusalPreventsUsingAnEarlierPromptedName() {
        var memory = memory(outbound("¿Qué nombre te gustaría que comparta?"),
                inbound("Ana López"),
                outbound("¿Desea que le conecte con un especialista Ford?"),
                inbound("No comparta mi nombre."),
                outbound("¿Desea continuar con el especialista?"));

        var packet = factory.createPacket(journey(Map.of()), "Sí, por favor.", memory);

        assertThat(packet.arguments()).doesNotContainKey("customerName");
    }

    @Test
    void laterNameRefusalPreventsUsingAnEarlierExplicitName() {
        var memory = memory(inbound("Me llamo Ana López."),
                outbound("¿Desea que le conecte con un especialista Ford?"),
                inbound("No comparta mi nombre."),
                outbound("¿Desea continuar con el especialista?"));

        var packet = factory.createPacket(journey(Map.of()), "Sí, por favor.", memory);

        assertThat(packet.arguments()).doesNotContainKey("customerName");
    }

    @Test
    void sameTurnNameSharingRefusalOmitsExplicitName() {
        var text = "Me llamo Ana López, pero no comparta mi nombre; busco una Ranger para viajar con Ana López.";
        var packet = factory.createPacket(journey(Map.of()), text);
        var payload = factory.create(text, memory(), "Solicitud registrada.");

        assertThat(packet.arguments()).doesNotContainKey("customerName");
        assertThat(payload.reason()).doesNotContain("Ana López");
        assertThat(payload.needs().orElse("")).doesNotContain("Ana López");
    }

    @Test
    void refusalSayingDoNotShareOmitsExplicitName() {
        var text = "Me llamo Ana López, pero no quiero que compartas mi nombre; busco una Ranger para viajar con Ana López.";
        var packet = factory.createPacket(journey(Map.of()), text);
        var payload = factory.create(text, memory(), "Solicitud registrada.");

        assertThat(packet.arguments()).doesNotContainKey("customerName");
        assertThat(payload.reason()).doesNotContain("Ana López");
        assertThat(payload.needs().orElse("")).doesNotContain("Ana López");
    }

    @Test
    void correctedNameReplacesPacketNameAndBothNamesAreScrubbedFromSummary() {
        var memory = memory(inbound("Me llamo Ana López y busco una Ranger para viajar con Ana López y mi familia."),
                outbound("¿Qué nombre te gustaría que comparta?"),
                inbound("Mi nombre es Laura Ruiz."),
                outbound("¿Desea que le conecte con un especialista Ford?"));
        var packet = factory.createPacket(journey(Map.of()), "Sí, por favor.", memory);
        var payload = factory.create("Sí, por favor.", memory, "Solicitud registrada.");

        assertThat(packet.arguments()).containsEntry("customerName", "Laura Ruiz");
        assertThat(payload.reason()).doesNotContain("Ana López", "Laura Ruiz");
        assertThat(payload.needs().orElse("")).doesNotContain("Ana López", "Laura Ruiz");
    }

    @Test
    void shorterCorrectedNameDoesNotLeaveOlderSurnameInSummary() {
        var memory = memory(inbound("Me llamo Ana López. Busco una Ranger para viajar con Ana López y mi familia."),
                outbound("¿Qué nombre te gustaría que comparta?"),
                inbound("Mi nombre es Ana."),
                outbound("¿Desea que le conecte con un especialista Ford?"));
        var packet = factory.createPacket(journey(Map.of()), "Sí, por favor.", memory);
        var payload = factory.create("Sí, por favor.", memory, "Solicitud registrada.");

        assertThat(packet.arguments()).containsEntry("customerName", "Ana");
        assertThat(payload.reason()).doesNotContain("López");
        assertThat(payload.needs().orElse("")).doesNotContain("López");
    }

    @Test
    void removesExtractedNameFromAllFreeTextFieldsButPreservesBuyerContext() {
        var message = "Me llamo Ana López. Busco una Ranger para viajar con Ana López y mi familia. "
                + "¿Puede Ana López confirmar la fecha?";
        var memory = memory(inbound(message));
        var payload = factory.create(message, memory, "No se pudo confirmar la fecha.");
        var packet = factory.createPacket(journey(Map.of()), message, memory);

        assertThat(packet.arguments()).containsEntry("customerName", "Ana López");
        assertThat(payload.reason()).doesNotContain("Ana López");
        assertThat(payload.vehicle()).contains("Ranger");
        assertThat(payload.needs().orElse("")).contains("viajar").doesNotContain("Ana López");
        assertThat(payload.unresolvedQuestion().orElse("")).contains("confirmar la fecha").doesNotContain("Ana López");
    }

    @Test
    void internalNoteSeparatesConfirmedFactsEvidenceAndUnknownsWithoutChangingBdcPacket() {
        var journey = journey(Map.of(
                LeadJourneyFactKey.VEHICLE_MODEL, fact("Ranger"),
                LeadJourneyFactKey.PAYMENT_METHOD, fact("financiamiento"),
                LeadJourneyFactKey.DOWN_PAYMENT, fact("20 mil pesos"),
                LeadJourneyFactKey.CUSTOMER_NAME, fact("María Elena")));
        var trace = List.of(new DecisionTraceEvent("tool", "tool_returned", "VEHICLE_DETAILS", "none",
                "search_vehicles", "returned", 2, Instant.parse("2026-10-21T12:00:00Z")));

        var decision = ConversationDecision.deterministicDefault().withVehicleEntities(
                new VehicleEntityResolver().resolve("Ranger XLT 2027", null));
        var note = factory.createInternalNote(journey, trace, decision, "HIGH");
        var packet = factory.createPacket(journey);

        assertThat(note.confirmedFacts()).extracting(InternalHandoffNote.Fact::field)
                .containsExactly("vehicle_model", "model_year", "vehicle_trim");
        assertThat(note.confirmedFacts()).extracting(InternalHandoffNote.Fact::value)
                .containsExactly("Ranger", "2027", "XLT");
        assertThat(note.confirmedFacts()).extracting(InternalHandoffNote.Fact::source)
                .containsExactly("lead_journey", "current_message", "current_message");
        assertThat(note.verifiedEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.source()).isEqualTo("search_vehicles");
            assertThat(evidence.outcome()).isEqualTo("returned");
            assertThat(evidence.evidenceCount()).isEqualTo(2);
            assertThat(evidence.freshness()).isEqualTo("not_recorded");
        });
        assertThat(note.unknowns()).contains("evidence_freshness").doesNotContain("model_year", "trim");
        assertThat(note.urgency()).isEqualTo("HIGH");
        assertThat(note.toString()).doesNotContain("financiamiento", "20 mil pesos", "María Elena");
        assertThat(packet.arguments().keySet()).allMatch(java.util.Set.of("vehicleModel", "vehicleTrim", "needs",
                "purchaseTimeframe", "buyerPriceRange", "tradeInInterest", "appointmentInterest", "customerName")::contains);
        assertThat(packet.arguments()).doesNotContainKey("customerName");
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
